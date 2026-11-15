'use strict';
// 실버패스 동기화 서버. 의존성 없이 Node 내장 모듈만 쓴다(공급망 공격면 최소화).
const http = require('node:http');
const { Store } = require('./store');
const { RateLimiter } = require('./ratelimit');
const { createNaver, OPTIONS } = require('./naver');

const MAX_BODY = 8 * 1024;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const TOKEN = /^(spa|spv)_[A-Za-z0-9_-]{43}$/;

class HttpError extends Error {
  constructor(status, code, extra) {
    super(code);
    this.status = status;
    this.code = code;
    this.extra = extra;
  }
}

// ---------- 입력 검증 ----------
function str(v, field, max) {
  if (typeof v !== 'string') throw new HttpError(400, 'invalid_field', { field });
  const s = v.normalize('NFC').trim();
  // 제어 문자 금지(로그·화면 오염 방지)
  if (!s || s.length > max || /[\u0000-\u001f\u007f-\u009f\u2028\u2029]/.test(s)) throw new HttpError(400, 'invalid_field', { field });
  return s;
}
function minuteOfDay(v, field) {
  if (!Number.isInteger(v) || v < 0 || v >= 1440 || v % 15 !== 0) throw new HttpError(400, 'invalid_field', { field });
  return v;
}
function coord(v, field) {
  const o = onlyKeys(v, ['lat', 'lon']);
  // 국내 경로만 다룬다(네이버 Directions 5 범위).
  if (typeof o.lat !== 'number' || typeof o.lon !== 'number' || o.lat < 33 || o.lat > 39.5 || o.lon < 124 || o.lon > 132) {
    throw new HttpError(400, 'invalid_field', { field });
  }
  return { lat: o.lat, lon: o.lon };
}
function place(v, field) {
  const o = onlyKeys(v, ['label', 'lat', 'lon']);
  return { label: str(o.label, `${field}.label`, 60), ...coord({ lat: o.lat, lon: o.lon }, field) };
}
function onlyKeys(obj, allowed) {
  if (obj === null || typeof obj !== 'object' || Array.isArray(obj)) throw new HttpError(400, 'invalid_body');
  for (const k of Object.keys(obj)) if (!allowed.includes(k)) throw new HttpError(400, 'unknown_field', { field: k });
  return obj;
}

async function readJson(req) {
  const ct = (req.headers['content-type'] || '').split(';')[0].trim().toLowerCase();
  if (ct !== 'application/json') throw new HttpError(415, 'unsupported_media_type');
  const len = Number(req.headers['content-length'] || 0);
  if (len > MAX_BODY) throw new HttpError(413, 'body_too_large');
  const chunks = [];
  let size = 0;
  for await (const c of req) {
    size += c.length;
    if (size > MAX_BODY) throw new HttpError(413, 'body_too_large');
    chunks.push(c);
  }
  if (size === 0) return {};
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {
    throw new HttpError(400, 'invalid_json');
  }
}

function send(res, status, body) {
  const data = body === undefined ? '' : JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(data),
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    'Content-Security-Policy': "default-src 'none'; frame-ancestors 'none'",
    'Referrer-Policy': 'no-referrer',
    'X-Frame-Options': 'DENY',
    'Cross-Origin-Resource-Policy': 'same-origin',
  });
  res.end(data);
}

function createServer({ store, now = () => Date.now(), trustProxy = false, maxAccounts = 2000, maxVehicles = 500, log = console.log, naver = createNaver() } = {}) {
  const rl = new RateLimiter({ now });
  const needNaver = () => { if (!naver.enabled) throw new HttpError(503, 'planning_unavailable'); };
  const upstream = async (fn) => {
    try {
      return await fn();
    } catch (e) {
      log(JSON.stringify({ level: 'warn', msg: `naver: ${String(e && e.message || e).slice(0, 200)}` }));
      throw new HttpError(502, 'route_service_failed');
    }
  };

  // 요청 제한(키, 창 길이 ms, 최대 횟수). 초과하면 429.
  const limit = (key, windowMs, max) => {
    if (!rl.hit(key, windowMs, max)) throw new HttpError(429, 'rate_limited');
  };

  const clientIp = (req) => {
    // 앞단 프록시(edge) 뒤에서만 X-Forwarded-For를 믿는다(서버 포트는 내부 네트워크에만 열려 있다).
    // 클라이언트가 보낸 값 뒤에 프록시가 덧붙이므로 마지막 값만 믿는다(앞쪽은 위조 가능).
    if (trustProxy) {
      const xff = req.headers['x-forwarded-for'];
      if (typeof xff === 'string' && xff) return xff.split(',').pop().trim().slice(0, 64);
    }
    return req.socket.remoteAddress || 'unknown';
  };

  const bearer = (req, prefix) => {
    const h = req.headers.authorization || '';
    const m = /^Bearer (\S+)$/.exec(h);
    if (!m || !TOKEN.test(m[1]) || !m[1].startsWith(prefix + '_')) throw new HttpError(401, 'unauthorized');
    return m[1];
  };
  const authAccount = (req) => {
    const a = store.accountByToken(bearer(req, 'spa'));
    if (!a) throw new HttpError(401, 'unauthorized');
    return a;
  };
  const authVehicle = (req) => {
    const v = store.vehicleByToken(bearer(req, 'spv'));
    if (!v) throw new HttpError(401, 'unauthorized');
    return v;
  };

  // [메서드, 경로 정규식, 처리 함수]. 경로 변수는 정규식 그룹으로만 받고 UUID 형식을 강제한다.
  const routes = [
    ['GET', /^\/healthz$/, () => [200, { ok: true }]],

    // ----- 계정(휴대전화) -----
    ['POST', /^\/v1\/accounts$/, async (req, ip) => {
      limit(`acct-create:${ip}`, 3600_000, 10);
      limit('acct-create:all', 3600_000, 200);
      const body = onlyKeys(await readJson(req), ['name']);
      const name = str(body.name, 'name', 20);
      if (store.count('accounts') >= maxAccounts) throw new HttpError(503, 'capacity_reached');
      return [201, store.createAccount(name)];
    }],
    ['GET', /^\/v1\/me$/, (req) => [200, store.accountView(authAccount(req).id)]],
    ['DELETE', /^\/v1\/me$/, (req) => { store.deleteAccount(authAccount(req).id); return [204]; }],
    ['PUT', /^\/v1\/me\/conditions$/, async (req) => {
      const a = authAccount(req);
      limit(`cond:${a.id}`, 60_000, 30);
      const body = onlyKeys(await readJson(req), ['quietHours', 'avoidHighway', 'version']);
      let quietHours = null;
      if (body.quietHours !== null) {
        const q = onlyKeys(body.quietHours, ['start', 'end']);
        quietHours = { start: minuteOfDay(q.start, 'quietHours.start'), end: minuteOfDay(q.end, 'quietHours.end') };
        if (quietHours.start === quietHours.end) throw new HttpError(400, 'invalid_field', { field: 'quietHours' });
      }
      if (typeof body.avoidHighway !== 'boolean') throw new HttpError(400, 'invalid_field', { field: 'avoidHighway' });
      if (!Number.isInteger(body.version) || body.version < 1) throw new HttpError(400, 'invalid_field', { field: 'version' });
      const r = store.updateConditions(a.id, { quietHours, avoidHighway: body.avoidHighway, version: body.version });
      if (r.error === 'version_conflict') throw new HttpError(409, 'version_conflict', { current: r.current });
      return [200, r.account];
    }],
    ['GET', /^\/v1\/me\/coupons$/, (req) => [200, store.couponSummary(authAccount(req).id)]],
    ['POST', /^\/v1\/me\/coupons\/redeem$/, async (req) => {
      const a = authAccount(req);
      limit(`redeem:${a.id}`, 60_000, 10);
      const body = onlyKeys(await readJson(req), ['requestId']);
      if (typeof body.requestId !== 'string' || !UUID.test(body.requestId)) throw new HttpError(400, 'invalid_field', { field: 'requestId' });
      const r = store.redeemCoupon(a.id, body.requestId.toLowerCase());
      if (r.error === 'limit_reached') throw new HttpError(409, 'limit_reached', { coupons: r.coupons });
      if (r.error) throw new HttpError(409, r.error);
      return [r.alreadyDone ? 200 : 201, r];
    }],
    // ----- 주행 계획(M07): 네이버 호출은 비용이 들어 계정·전체 단위로 제한한다 -----
    ['POST', /^\/v1\/plan\/geocode$/, async (req) => {
      const a = authAccount(req);
      needNaver();
      limit(`geo:${a.id}`, 600_000, 30);
      limit('geo:all', 3600_000, 1000);
      const body = onlyKeys(await readJson(req), ['query']);
      const query = str(body.query, 'query', 80);
      // 장소 이름("부산역") 먼저, 그다음 주소. 한쪽이 실패해도 다른 쪽 결과는 돌려준다.
      const [places, addresses] = await Promise.allSettled([
        naver.places ? naver.places(query) : Promise.resolve([]),
        naver.geocode(query),
      ]);
      if (places.status === 'rejected' && addresses.status === 'rejected') {
        log(JSON.stringify({ level: 'warn', msg: `naver: ${String(places.reason && places.reason.message).slice(0, 120)} / ${String(addresses.reason && addresses.reason.message).slice(0, 120)}` }));
        throw new HttpError(502, 'route_service_failed');
      }
      const seen = new Set();
      // 주소로 정확히 찾아지면(도로명·동 이름) 주소가 먼저, 아니면 장소 이름 검색 결과가 먼저다.
      const addr = addresses.value || [];
      const results = (addr.length ? [...addr, ...(places.value || [])] : [...(places.value || [])])
        .filter((r) => { const k = `${r.lat.toFixed(4)},${r.lon.toFixed(4)}`; if (seen.has(k)) return false; seen.add(k); return true; })
        .slice(0, 8)
        .map(({ address, lat, lon }) => ({ address, lat, lon }));
      return [200, { results }];
    }],
    ['POST', /^\/v1\/plan\/routes$/, async (req) => {
      const a = authAccount(req);
      needNaver();
      limit(`route:${a.id}`, 600_000, 20);
      limit('route:all', 3600_000, 300);
      const body = onlyKeys(await readJson(req), ['start', 'goal']);
      const start = coord(body.start, 'start');
      const goal = coord(body.goal, 'goal');
      return [200, { routes: await upstream(() => naver.routes(start, goal)) }];
    }],
    ['GET', /^\/v1\/me\/plan$/, (req) => [200, { plan: store.plan(authAccount(req).id) }]],
    ['PUT', /^\/v1\/me\/plan$/, async (req) => {
      const a = authAccount(req);
      limit(`plan:${a.id}`, 60_000, 20);
      const b = onlyKeys(await readJson(req), ['origin', 'destination', 'departAt', 'option', 'durationS', 'distanceM', 'tollFare', 'highways']);
      const t = now();
      if (!Number.isInteger(b.departAt) || b.departAt < t - 3600_000 || b.departAt > t + 14 * 86400_000) throw new HttpError(400, 'invalid_field', { field: 'departAt' });
      const opt = OPTIONS.find((o) => o.id === b.option);
      if (!opt) throw new HttpError(400, 'invalid_field', { field: 'option' });
      for (const f of ['durationS', 'distanceM', 'tollFare']) {
        if (!Number.isInteger(b[f]) || b[f] < 0 || b[f] > 10_000_000) throw new HttpError(400, 'invalid_field', { field: f });
      }
      if (!Array.isArray(b.highways) || b.highways.length > 20) throw new HttpError(400, 'invalid_field', { field: 'highways' });
      const plan = {
        origin: place(b.origin, 'origin'),
        destination: place(b.destination, 'destination'),
        departAt: b.departAt,
        option: opt.id,
        optionLabel: opt.label,
        naviOption: opt.navi,
        durationS: b.durationS,
        distanceM: b.distanceM,
        tollFare: b.tollFare,
        highways: b.highways.map((h, i) => str(h, `highways[${i}]`, 30)),
      };
      return [200, { plan: store.savePlan(a.id, plan) }];
    }],
    ['DELETE', /^\/v1\/me\/plan$/, (req) => { store.deletePlan(authAccount(req).id); return [204]; }],
    ['GET', /^\/v1\/me\/vehicles$/, (req) => [200, { vehicles: store.linkedVehicles(authAccount(req).id) }]],
    ['DELETE', /^\/v1\/me\/vehicles\/([0-9a-f-]{36})$/, (req, ip, m) => {
      const a = authAccount(req);
      if (!UUID.test(m[1]) || !store.unlink(m[1], a.id)) throw new HttpError(404, 'not_found');
      return [204];
    }],
    ['POST', /^\/v1\/pairings\/claim$/, async (req, ip) => {
      const a = authAccount(req);
      // 6자리 추측 방지: 계정·IP·전체 단위로 시도 횟수를 묶는다.
      limit(`claim:${a.id}`, 600_000, 5);
      limit(`claim-ip:${ip}`, 600_000, 10);
      limit('claim:all', 600_000, 100);
      const body = onlyKeys(await readJson(req), ['code']);
      if (typeof body.code !== 'string' || !/^\d{6}$/.test(body.code)) throw new HttpError(400, 'invalid_field', { field: 'code' });
      const r = store.claimPairingCode(a.id, body.code);
      if (r.error === 'invalid_code') throw new HttpError(404, 'invalid_code');
      if (r.error === 'expired_code') throw new HttpError(410, 'expired_code');
      return [200, r];
    }],

    // ----- 차량 -----
    ['POST', /^\/v1\/vehicles$/, async (req, ip) => {
      limit(`veh-create:${ip}`, 3600_000, 10);
      limit('veh-create:all', 3600_000, 100);
      const body = onlyKeys(await readJson(req), ['name']);
      const name = str(body.name, 'name', 30);
      if (store.count('vehicles') >= maxVehicles) throw new HttpError(503, 'capacity_reached');
      return [201, store.createVehicle(name)];
    }],
    ['DELETE', /^\/v1\/vehicle$/, (req) => { store.deleteVehicle(authVehicle(req).id); return [204]; }],
    ['POST', /^\/v1\/vehicle\/pairing-codes$/, (req) => {
      const v = authVehicle(req);
      limit(`pair-issue:${v.id}`, 600_000, 20);
      return [201, store.issuePairingCode(v.id)];
    }],
    ['GET', /^\/v1\/vehicle\/accounts$/, (req) => [200, { accounts: store.linkedAccounts(authVehicle(req).id) }]],
    ['DELETE', /^\/v1\/vehicle\/accounts\/([0-9a-f-]{36})$/, (req, ip, m) => {
      const v = authVehicle(req);
      if (!UUID.test(m[1]) || !store.unlink(v.id, m[1])) throw new HttpError(404, 'not_found');
      return [204];
    }],
  ];

  const server = http.createServer(async (req, res) => {
    const started = now();
    const ip = clientIp(req);
    let status = 500;
    try {
      limit(`ip:${ip}`, 60_000, 120);
      const url = new URL(req.url, 'http://x');
      if (url.search) {
        // 쿼리 문자열을 받는 API가 없다.
        throw new HttpError(400, 'unexpected_query');
      }
      const path = url.pathname;
      const sameRoute = routes.filter(([, re]) => re.test(path));
      if (!sameRoute.length) throw new HttpError(404, 'not_found');
      const route = sameRoute.find(([m]) => m === req.method);
      if (!route) throw new HttpError(405, 'method_not_allowed');
      const [s, body] = await route[2](req, ip, path.match(route[1]));
      status = s;
      send(res, s, body);
    } catch (e) {
      if (e instanceof HttpError) {
        status = e.status;
        send(res, e.status, { error: e.code, ...(e.extra || {}) });
      } else {
        status = 500;
        log(JSON.stringify({ level: 'error', msg: String(e && e.stack || e).slice(0, 2000) }));
        if (!res.headersSent) send(res, 500, { error: 'internal' });
      }
    } finally {
      // 토큰·본문은 남기지 않는다.
      log(JSON.stringify({ t: new Date(started).toISOString(), ip, m: req.method, p: String(req.url).slice(0, 120), s: status, ms: now() - started }));
    }
  });

  server.requestTimeout = 10_000;
  server.headersTimeout = 5_000;
  server.keepAliveTimeout = 5_000;
  server.maxHeadersCount = 50;
  server.maxRequestsPerSocket = 200;
  return server;
}

if (require.main === module) {
  const port = Number(process.env.PORT || 8080);
  const store = new Store(process.env.DB_PATH || '/data/silverpass.db');
  // 네이버 출구 프록시 주소. 설정돼 있으면 네이버 키 없이 이 프록시로만 나간다(키는 프록시에만 둔다).
  const proxyHost = process.env.NAVER_EGRESS_SERVICE_HOST;
  const naver = createNaver({
    proxyUrl: proxyHost ? `http://${proxyHost}:${process.env.NAVER_EGRESS_SERVICE_PORT || 8080}` : undefined,
    clientId: process.env.NAVER_MAPS_CLIENT_ID,
    clientSecret: process.env.NAVER_MAPS_CLIENT_SECRET,
    searchId: process.env.SEARCH_CLIENT_ID,
    searchSecret: process.env.SEARCH_CLIENT_SECRET,
  });
  const server = createServer({
    store,
    trustProxy: process.env.TRUST_PROXY === '1',
    maxAccounts: Number(process.env.MAX_ACCOUNTS || 2000),
    maxVehicles: Number(process.env.MAX_VEHICLES || 500),
    naver,
  });
  server.listen(port, '0.0.0.0', () => console.log(JSON.stringify({ level: 'info', msg: `listening on ${port}` })));
  const stop = () => server.close(() => { store.close(); process.exit(0); });
  process.on('SIGTERM', stop);
  process.on('SIGINT', stop);
}

module.exports = { createServer };
