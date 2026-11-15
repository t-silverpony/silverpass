'use strict';
// node --test server/test — 기능과 보안(인증·권한 분리·입력 검증·요청 제한·중복 차감) 테스트
const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { Store, monthOf } = require('../src/store');
const { createServer } = require('../src/server');

// 단언이 실패해 close()를 못 부른 테스트도 끝나면 서버를 닫는다.
const servers = [];
test.after(() => servers.forEach((s) => { s.closeAllConnections(); s.close(); }));

function setup(opts = {}) {
  let clock = Date.parse('2026-11-10T09:30:00Z'); // KST 18:30
  const now = () => clock;
  const store = new Store(':memory:', { now });
  const server = createServer({ store, now, log: () => {}, ...opts });
  servers.push(server);
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => {
    const base = `http://127.0.0.1:${server.address().port}`;
    const call = async (method, path, { token, body, headers = {}, raw } = {}) => {
      const h = { ...headers };
      if (token) h.authorization = `Bearer ${token}`;
      if (body !== undefined || raw !== undefined) h['content-type'] = h['content-type'] || 'application/json';
      const r = await fetch(base + path, { method, headers: h, body: raw !== undefined ? raw : body !== undefined ? JSON.stringify(body) : undefined });
      const text = await r.text();
      return { status: r.status, body: text ? JSON.parse(text) : undefined, headers: r.headers };
    };
    resolve({ call, store, close: () => new Promise((r) => { server.close(r); server.closeAllConnections(); }), advance: (ms) => { clock += ms; } });
  }));
}

async function account(call, name = '김실버') {
  const r = await call('POST', '/v1/accounts', { body: { name } });
  assert.equal(r.status, 201);
  return r.body;
}
async function vehicle(call, name = '아이오닉 5') {
  const r = await call('POST', '/v1/vehicles', { body: { name } });
  assert.equal(r.status, 201);
  return r.body;
}

test('계정 생성 → 조건 수정 → 차량 연결 → 차량에서 같은 조건 조회', async () => {
  const { call, close } = await setup();
  const a = await account(call);
  assert.match(a.token, /^spa_/);
  assert.equal(a.account.conditions.quietHours, null);
  const upd = await call('PUT', '/v1/me/conditions', { token: a.token, body: { quietHours: { start: 1080, end: 360 }, avoidHighway: true, version: 1 } });
  assert.equal(upd.status, 200);
  assert.equal(upd.body.version, 2);

  const v = await vehicle(call);
  const code = await call('POST', '/v1/vehicle/pairing-codes', { token: v.token });
  assert.match(code.body.code, /^\d{6}$/);
  const claim = await call('POST', '/v1/pairings/claim', { token: a.token, body: { code: code.body.code } });
  assert.equal(claim.status, 200);
  assert.equal(claim.body.vehicle.id, v.vehicle.id);

  const list = await call('GET', '/v1/vehicle/accounts', { token: v.token });
  assert.equal(list.body.accounts.length, 1);
  assert.deepEqual(list.body.accounts[0].conditions, { quietHours: { start: 1080, end: 360 }, avoidHighway: true });
  await close();
});

test('다른 기기가 먼저 고쳤으면 version 충돌(409)로 덮어쓰지 않음', async () => {
  const { call, close } = await setup();
  const a = await account(call);
  const body = { quietHours: null, avoidHighway: true, version: 1 };
  assert.equal((await call('PUT', '/v1/me/conditions', { token: a.token, body })).status, 200);
  const r = await call('PUT', '/v1/me/conditions', { token: a.token, body: { ...body, avoidHighway: false } });
  assert.equal(r.status, 409);
  assert.equal(r.body.error, 'version_conflict');
  assert.equal(r.body.current.conditions.avoidHighway, true);
  await close();
});

test('할인권: 월 2회, 같은 requestId는 한 번만, 다른 계정 requestId 재사용 거절, 다음 달 초기화', async () => {
  const { call, close, advance } = await setup();
  const a = await account(call);
  const b = await account(call, '박이동');
  const r1 = crypto.randomUUID();
  const first = await call('POST', '/v1/me/coupons/redeem', { token: a.token, body: { requestId: r1 } });
  assert.equal(first.status, 201);
  assert.equal(first.body.coupons.remaining, 1);
  const again = await call('POST', '/v1/me/coupons/redeem', { token: a.token, body: { requestId: r1 } });
  assert.equal(again.status, 200);
  assert.equal(again.body.alreadyDone, true);
  assert.equal(again.body.coupons.remaining, 1);
  assert.equal((await call('POST', '/v1/me/coupons/redeem', { token: b.token, body: { requestId: r1 } })).status, 409);
  assert.equal((await call('POST', '/v1/me/coupons/redeem', { token: a.token, body: { requestId: crypto.randomUUID() } })).status, 201);
  const over = await call('POST', '/v1/me/coupons/redeem', { token: a.token, body: { requestId: crypto.randomUUID() } });
  assert.equal(over.status, 409);
  assert.equal(over.body.error, 'limit_reached');
  assert.equal((await call('GET', '/v1/me/coupons', { token: b.token })).body.remaining, 2);
  advance(25 * 24 * 3600_000); // 12월
  assert.equal((await call('GET', '/v1/me/coupons', { token: a.token })).body.remaining, 2);
  await close();
});

test('월은 한국 시각 기준', () => {
  assert.equal(monthOf(Date.parse('2026-11-30T15:00:00Z')), '2026-12'); // KST 12/1 00:00
  assert.equal(monthOf(Date.parse('2026-11-30T14:59:59Z')), '2026-11');
});

test('연결번호: 1회용, 5분 만료, 새 번호를 받으면 이전 번호 무효', async () => {
  const { call, close, advance } = await setup();
  const v = await vehicle(call);
  const a = await account(call);
  const b = await account(call, '박이동');
  const c1 = (await call('POST', '/v1/vehicle/pairing-codes', { token: v.token })).body.code;
  const c2 = (await call('POST', '/v1/vehicle/pairing-codes', { token: v.token })).body.code;
  if (c1 !== c2) assert.equal((await call('POST', '/v1/pairings/claim', { token: a.token, body: { code: c1 } })).status, 404);
  assert.equal((await call('POST', '/v1/pairings/claim', { token: a.token, body: { code: c2 } })).status, 200);
  assert.equal((await call('POST', '/v1/pairings/claim', { token: b.token, body: { code: c2 } })).status, 404, '재사용 불가');
  const c3 = (await call('POST', '/v1/vehicle/pairing-codes', { token: v.token })).body.code;
  advance(5 * 60_000 + 1);
  assert.equal((await call('POST', '/v1/pairings/claim', { token: b.token, body: { code: c3 } })).status, 410);
  await close();
});

test('연결번호 추측은 계정당 10분에 5번까지', async () => {
  const { call, close } = await setup();
  const a = await account(call);
  const statuses = [];
  for (let i = 0; i < 7; i++) {
    statuses.push((await call('POST', '/v1/pairings/claim', { token: a.token, body: { code: String(100000 + i) } })).status);
  }
  assert.deepEqual(statuses, [404, 404, 404, 404, 404, 429, 429]);
  await close();
});

test('인증: 토큰 없음·위조·종류 바꿔치기는 401', async () => {
  const { call, close } = await setup();
  const a = await account(call);
  const v = await vehicle(call);
  assert.equal((await call('GET', '/v1/me')).status, 401);
  assert.equal((await call('GET', '/v1/me', { token: 'spa_' + 'A'.repeat(43) })).status, 401);
  assert.equal((await call('GET', '/v1/me', { token: v.token })).status, 401, '차량 토큰으로 계정 API 불가');
  assert.equal((await call('GET', '/v1/vehicle/accounts', { token: a.token })).status, 401, '계정 토큰으로 차량 API 불가');
  assert.equal((await call('GET', '/v1/me', { headers: { authorization: `Basic ${a.token}` } })).status, 401);
  await close();
});

test('권한 분리: 연결 안 된 차량은 계정 정보를 못 보고, 남의 연결을 못 끊음', async () => {
  const { call, close } = await setup();
  const a = await account(call);
  const b = await account(call, '박이동');
  const v1 = await vehicle(call);
  const v2 = await vehicle(call, '다른 차');
  const code = (await call('POST', '/v1/vehicle/pairing-codes', { token: v1.token })).body.code;
  await call('POST', '/v1/pairings/claim', { token: a.token, body: { code } });
  assert.equal((await call('GET', '/v1/vehicle/accounts', { token: v2.token })).body.accounts.length, 0);
  assert.equal((await call('DELETE', `/v1/vehicle/accounts/${a.account.id}`, { token: v2.token })).status, 404);
  assert.equal((await call('DELETE', `/v1/me/vehicles/${v1.vehicle.id}`, { token: b.token })).status, 404);
  assert.equal((await call('GET', '/v1/vehicle/accounts', { token: v1.token })).body.accounts.length, 1);
  // 연결 해제 후 차량에서 사라짐(E01-03)
  assert.equal((await call('DELETE', `/v1/me/vehicles/${v1.vehicle.id}`, { token: a.token })).status, 204);
  assert.equal((await call('GET', '/v1/vehicle/accounts', { token: v1.token })).body.accounts.length, 0);
  await close();
});

test('계정 삭제 시 할인권·연결도 함께 삭제되고 토큰은 무효', async () => {
  const { call, close, store } = await setup();
  const a = await account(call);
  const v = await vehicle(call);
  const code = (await call('POST', '/v1/vehicle/pairing-codes', { token: v.token })).body.code;
  await call('POST', '/v1/pairings/claim', { token: a.token, body: { code } });
  await call('POST', '/v1/me/coupons/redeem', { token: a.token, body: { requestId: crypto.randomUUID() } });
  assert.equal((await call('DELETE', '/v1/me', { token: a.token })).status, 204);
  assert.equal((await call('GET', '/v1/me', { token: a.token })).status, 401);
  assert.equal(store.count('coupon_uses'), 0);
  assert.equal(store.count('links'), 0);
  await close();
});

test('입력 검증: 알 수 없는 필드, 잘못된 시각, 제어 문자, 큰 본문, 잘못된 JSON, Content-Type', async () => {
  const { call, close } = await setup();
  const a = await account(call);
  const put = (body) => call('PUT', '/v1/me/conditions', { token: a.token, body });
  assert.equal((await put({ quietHours: null, avoidHighway: true, version: 1, admin: true })).status, 400);
  assert.equal((await put({ quietHours: { start: 1440, end: 0 }, avoidHighway: true, version: 1 })).status, 400);
  assert.equal((await put({ quietHours: { start: 1081, end: 0 }, avoidHighway: true, version: 1 })).status, 400);
  assert.equal((await put({ quietHours: { start: 60, end: 60 }, avoidHighway: true, version: 1 })).status, 400);
  assert.equal((await put({ quietHours: { start: '1080', end: 360 }, avoidHighway: true, version: 1 })).status, 400);
  assert.equal((await put({ quietHours: null, avoidHighway: 'yes', version: 1 })).status, 400);
  assert.equal((await put([1, 2])).status, 400);
  assert.equal((await call('POST', '/v1/accounts', { body: { name: '김\u0000실버' } })).status, 400);
  assert.equal((await call('POST', '/v1/accounts', { body: { name: 'x'.repeat(21) } })).status, 400);
  assert.equal((await call('POST', '/v1/accounts', { body: { name: { $gt: '' } } })).status, 400);
  assert.equal((await call('POST', '/v1/accounts', { raw: '{"name":' })).status, 400);
  assert.equal((await call('POST', '/v1/accounts', { raw: JSON.stringify({ name: 'a'.repeat(9000) }) })).status, 413);
  assert.equal((await call('POST', '/v1/accounts', { raw: '{"name":"a"}', headers: { 'content-type': 'text/plain' } })).status, 415);
  assert.equal((await call('POST', '/v1/me/coupons/redeem', { token: a.token, body: { requestId: "1' OR '1'='1" } })).status, 400);
  assert.equal((await call('DELETE', '/v1/me/vehicles/..%2f..%2fetc', { token: a.token })).status, 404);
  assert.equal((await call('GET', '/v1/me?debug=1', { token: a.token })).status, 400);
  await close();
});

test('SQL 주입 시도 이름도 그대로 문자열로 저장', async () => {
  const { call, close, store } = await setup();
  const a = await account(call, "'; DROP TABLE x;--");
  assert.equal(a.account.name, "'; DROP TABLE x;--");
  assert.equal(store.count('accounts'), 1);
  await close();
});

test('응답 보안 헤더, 없는 경로 404, 다른 메서드 405', async () => {
  const { call, close } = await setup();
  const r = await call('GET', '/healthz');
  assert.equal(r.headers.get('cache-control'), 'no-store');
  assert.equal(r.headers.get('x-content-type-options'), 'nosniff');
  assert.match(r.headers.get('content-security-policy'), /default-src 'none'/);
  assert.equal(r.headers.get('x-powered-by'), null);
  assert.equal((await call('GET', '/admin')).status, 404);
  assert.equal((await call('DELETE', '/v1/accounts')).status, 405);
  await close();
});

test('계정 생성은 IP당 시간당 10개, 전체 상한 초과 시 503', async () => {
  const { call, close } = await setup({ maxAccounts: 12 });
  const s = [];
  for (let i = 0; i < 11; i++) s.push((await call('POST', '/v1/accounts', { body: { name: `u${i}` } })).status);
  assert.deepEqual(s.slice(0, 10), Array(10).fill(201));
  assert.equal(s[10], 429);
  await close();
  const t = await setup({ maxAccounts: 1 });
  await account(t.call);
  assert.equal((await t.call('POST', '/v1/accounts', { body: { name: 'x' } })).status, 503);
  await t.close();
});

test('trustProxy일 때 X-Forwarded-For 마지막 값만 사용(앞쪽 위조로 제한 우회 불가)', async () => {
  const { call, close } = await setup({ trustProxy: true });
  const s = [];
  for (let i = 0; i < 11; i++) {
    s.push((await call('POST', '/v1/accounts', { body: { name: `u${i}` }, headers: { 'x-forwarded-for': `10.0.0.${i}, 203.0.113.7` } })).status);
  }
  assert.equal(s[10], 429);
  await close();
});

// ---------- 주행 계획(M07) ----------
const { highwayNames, simplify } = require('../src/naver');

function fakeNaver(calls = []) {
  return {
    enabled: true,
    async geocode(q) { calls.push(['geo', q]); return q === '부산역' ? [] : [{ address: '부산광역시 동구 중앙대로 206', lat: 35.11519, lon: 129.04161 }]; },
    async places(q) { calls.push(['place', q]); return [{ address: '부산역 (고속철도) · 부산광역시 동구 중앙대로 206', name: '부산역 (고속철도)', lat: 35.11519, lon: 129.04160 }, { address: '부산역 부산1호선 · 부산광역시 동구 중앙대로 200', lat: 35.1145, lon: 129.0393 }]; },
    async routes(s, g) {
      calls.push(['route', s, g]);
      return [
        { option: 'traoptimal', label: '추천 경로', naviOption: 'RECOMMENDED', distanceM: 393000, durationS: 15480, tollFare: 21300, highways: ['경부고속도로'], path: [[37.4, 127.1], [35.1, 129.0]] },
        { option: 'traavoidtoll', label: '무료도로 우선', naviOption: 'FREE', distanceM: 407000, durationS: 23220, tollFare: 0, highways: [], path: [[37.4, 127.1], [35.1, 129.0]] },
      ];
    },
  };
}

test('고속국도 이름 추출: 도시고속도로 제외, 중복 제거 / 경로 좌표 줄이기', () => {
  assert.deepEqual(highwayNames([{ name: '경부고속도로' }, { name: '경부고속도로' }, { name: '부산도시고속도로' }, { name: '올림픽대로' }, { name: '중부내륙고속도로' }]),
    ['경부고속도로', '중부내륙고속도로']);
  const long = Array.from({ length: 1000 }, (_, i) => [127 + i / 1000, 37]);
  const s = simplify(long, 150);
  assert.equal(s.length, 150);
  assert.deepEqual(s[0], [37, 127]);
  assert.deepEqual(s[149], [37, 127.999]);
});

test('주행 계획: 주소 검색·경로 옵션·계획 저장 → 차량에서 같은 계획 조회', async () => {
  const calls = [];
  const { call, close } = await setup({ naver: fakeNaver(calls) });
  const a = await account(call);
  const g = await call('POST', '/v1/plan/geocode', { token: a.token, body: { query: '부산광역시 동구 중앙대로 206' } });
  assert.equal(g.status, 200);
  // 주소로 정확히 찾아지면 주소가 먼저, 같은 지점(소수 넷째 자리)의 장소 결과는 합친다.
  assert.equal(g.body.results[0].address, '부산광역시 동구 중앙대로 206');
  assert.equal(g.body.results.length, 2);
  assert.deepEqual(Object.keys(g.body.results[0]).sort(), ['address', 'lat', 'lon']);
  // 주소로 안 찾아지는 장소 이름은 장소 검색 결과가 그대로 온다.
  const byName = await call('POST', '/v1/plan/geocode', { token: a.token, body: { query: '부산역' } });
  assert.equal(byName.body.results[0].address, '부산역 (고속철도) · 부산광역시 동구 중앙대로 206');
  assert.equal(byName.body.results.length, 2);
  const r = await call('POST', '/v1/plan/routes', { token: a.token, body: { start: { lat: 37.41, lon: 127.09 }, goal: { lat: 35.11, lon: 129.04 } } });
  assert.equal(r.status, 200);
  assert.equal(r.body.routes.length, 2);
  const departAt = Date.parse('2026-11-10T10:00:00Z');
  const plan = {
    origin: { label: '성남시 수정구 시흥동', lat: 37.42, lon: 127.1 }, destination: { label: '부산역', lat: 35.11, lon: 129.04 },
    departAt, option: 'traavoidtoll', durationS: 23220, distanceM: 407000, tollFare: 0, highways: [],
  };
  const put = await call('PUT', '/v1/me/plan', { token: a.token, body: plan });
  assert.equal(put.status, 200);
  assert.equal(put.body.plan.naviOption, 'FREE');
  const v = await vehicle(call);
  const code = (await call('POST', '/v1/vehicle/pairing-codes', { token: v.token })).body.code;
  await call('POST', '/v1/pairings/claim', { token: a.token, body: { code } });
  const acc = (await call('GET', '/v1/vehicle/accounts', { token: v.token })).body.accounts[0];
  assert.equal(acc.plan.destination.label, '부산역');
  assert.equal(acc.plan.departAt, departAt);
  assert.equal((await call('DELETE', '/v1/me/plan', { token: a.token })).status, 204);
  assert.equal((await call('GET', '/v1/me/plan', { token: a.token })).body.plan, null);
  await close();
});

test('주행 계획 입력 검증: 국외 좌표·잘못된 옵션·먼 출발 시각·알 수 없는 필드·인증', async () => {
  const { call, close } = await setup({ naver: fakeNaver() });
  const a = await account(call);
  assert.equal((await call('POST', '/v1/plan/routes', { token: a.token, body: { start: { lat: 40.7, lon: -74 }, goal: { lat: 35, lon: 129 } } })).status, 400);
  assert.equal((await call('POST', '/v1/plan/routes', { body: { start: { lat: 37, lon: 127 }, goal: { lat: 35, lon: 129 } } })).status, 401);
  assert.equal((await call('POST', '/v1/plan/geocode', { token: a.token, body: { query: 'x'.repeat(81) } })).status, 400);
  const base = {
    origin: { label: 'a', lat: 37, lon: 127 }, destination: { label: 'b', lat: 35, lon: 129 },
    departAt: Date.parse('2026-11-10T10:00:00Z'), option: 'traoptimal', durationS: 1, distanceM: 1, tollFare: 0, highways: [],
  };
  assert.equal((await call('PUT', '/v1/me/plan', { token: a.token, body: { ...base, option: 'evil' } })).status, 400);
  assert.equal((await call('PUT', '/v1/me/plan', { token: a.token, body: { ...base, departAt: base.departAt + 30 * 86400_000 } })).status, 400);
  assert.equal((await call('PUT', '/v1/me/plan', { token: a.token, body: { ...base, extra: 1 } })).status, 400);
  assert.equal((await call('PUT', '/v1/me/plan', { token: a.token, body: { ...base, highways: Array(21).fill('x') } })).status, 400);
  await close();
});

test('경로 검색은 계정당 10분에 20번까지(네이버 비용 보호), 키가 없으면 503, 네이버 오류는 502', async () => {
  const { call, close } = await setup({ naver: fakeNaver() });
  const a = await account(call);
  const body = { start: { lat: 37, lon: 127 }, goal: { lat: 35, lon: 129 } };
  const s = [];
  for (let i = 0; i < 21; i++) s.push((await call('POST', '/v1/plan/routes', { token: a.token, body })).status);
  assert.equal(s.filter((x) => x === 200).length, 20);
  assert.equal(s[20], 429);
  await close();
  const off = await setup({ naver: { enabled: false } });
  const b = await account(off.call);
  assert.equal((await off.call('POST', '/v1/plan/routes', { token: b.token, body })).status, 503);
  await off.close();
  const bad = await setup({ naver: { enabled: true, routes: async () => { throw new Error('boom'); } } });
  const c = await account(bad.call);
  assert.equal((await bad.call('POST', '/v1/plan/routes', { token: c.token, body })).status, 502);
  await bad.close();
});

test('장소 검색: 제목 태그 제거·좌표 변환·국외 좌표 제외, 한쪽 실패해도 다른 결과 반환', async () => {
  const { plain, createNaver } = require('../src/naver');
  assert.equal(plain('<b>부산역</b> &amp; 광장'), '부산역 & 광장');
  const fake = async (url) => ({
    ok: true,
    json: async () => (url.includes('/search/v1/local')
      ? { items: [{ title: '<b>부산역</b>', roadAddress: '부산광역시 동구 중앙대로 206', mapx: '1290416007', mapy: '351151869' }, { title: '해외', mapx: '-740000000', mapy: '407000000' }] }
      : { addresses: [] }),
  });
  const n = createNaver({ proxyUrl: 'http://proxy', fetchImpl: fake });
  const p = await n.places('부산역');
  assert.equal(p.length, 1);
  assert.equal(p[0].address, '부산역 · 부산광역시 동구 중앙대로 206');
  assert.ok(Math.abs(p[0].lat - 35.1151869) < 1e-9);
  const half = await setup({ naver: { enabled: true, places: async () => { throw new Error('down'); }, geocode: async () => [{ address: '주소', lat: 35, lon: 129 }] } });
  const a = await account(half.call);
  const r = await half.call('POST', '/v1/plan/geocode', { token: a.token, body: { query: '주소' } });
  assert.equal(r.status, 200);
  assert.equal(r.body.results.length, 1);
  await half.close();
});
