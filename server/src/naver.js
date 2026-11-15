'use strict';
// 네이버 클라우드 Maps(Directions 5, Geocoding) 호출.
// 운영: 키 없이 같은 ns의 naver-egress 프록시(nginx)로 보낸다. 키와 인터넷 연결은 프록시만 가진다.
// 개발: 키를 주면 네이버에 직접 호출한다.
const NAVER = 'https://maps.apigw.ntruss.com';

/** 경로 옵션: 화면 이름과 내비(NaviHelper RouteOption)로 옮길 때 가장 가까운 값 */
const OPTIONS = [
  { id: 'traoptimal', label: '추천 경로', navi: 'RECOMMENDED' },
  { id: 'traavoidcaronly', label: '자동차 전용도로 피하기', navi: 'FREE' },
  { id: 'traavoidtoll', label: '무료도로 우선', navi: 'FREE' },
];

/** 고속국도 이름만 고른다(팀 결정: 도시고속도로 제외). 차량 쪽 HighwayDetector와 같은 기준. */
function highwayNames(sections) {
  const out = [];
  for (const s of sections || []) {
    const m = String(s.name || '').match(/[가-힣0-9]+고속도로/g) || [];
    for (const n of m) if (!n.includes('도시고속') && !out.includes(n)) out.push(n);
  }
  return out;
}

/** 지도 그리기용으로 경로 좌표를 줄인다(최대 n개, 처음·끝 유지). [lat, lon] */
function simplify(path, n = 150) {
  if (!Array.isArray(path) || path.length <= n) return (path || []).map(([lon, lat]) => [lat, lon]);
  const step = (path.length - 1) / (n - 1);
  const out = [];
  for (let i = 0; i < n; i++) {
    const [lon, lat] = path[Math.round(i * step)];
    out.push([lat, lon]);
  }
  return out;
}

const API_HUB = 'https://naverapihub.apigw.ntruss.com';

/** 검색 결과 제목의 <b> 같은 태그와 HTML 엔티티를 없앤다. */
function plain(s) {
  return String(s || '').replace(/<[^>]*>/g, '').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"').replace(/&#39;/g, "'").trim();
}

function createNaver({ clientId, clientSecret, searchId, searchSecret, proxyUrl, fetchImpl = fetch } = {}) {
  const enabled = !!(proxyUrl || (clientId && clientSecret));
  const searchEnabled = !!(proxyUrl || (searchId && searchSecret));
  const base = proxyUrl || NAVER;
  const headers = proxyUrl ? {} : { 'x-ncp-apigw-api-key-id': clientId, 'x-ncp-apigw-api-key': clientSecret };
  const searchBase = proxyUrl || API_HUB;
  const searchHeaders = proxyUrl ? {} : { 'x-ncp-apigw-api-key-id': searchId, 'x-ncp-apigw-api-key': searchSecret };
  const call = async (path, b = base, h = headers) => {
    const r = await fetchImpl(b + path, { headers: h, signal: AbortSignal.timeout(8000) });
    if (!r.ok) throw new Error(`naver ${r.status}`);
    return r.json();
  };
  return {
    enabled,
    /** 장소 이름 검색(NAVER API HUB 검색-지역). 좌표는 WGS84 × 10^7 정수로 온다. */
    async places(query) {
      if (!searchEnabled) return [];
      const j = await call(`/search/v1/local?query=${encodeURIComponent(query)}&display=5&format=json`, searchBase, searchHeaders);
      return (j.items || []).slice(0, 5).map((i) => {
        const name = plain(i.title);
        const addr = i.roadAddress || i.address || '';
        return { address: addr ? `${name} · ${addr}` : name, name, lat: Number(i.mapy) / 1e7, lon: Number(i.mapx) / 1e7 };
      }).filter((p) => p.lat > 33 && p.lat < 39.5 && p.lon > 124 && p.lon < 132);
    },
    async geocode(query) {
      const j = await call(`/map-geocode/v2/geocode?query=${encodeURIComponent(query)}&count=5`);
      return (j.addresses || []).slice(0, 5).map((a) => ({
        address: a.roadAddress || a.jibunAddress,
        lat: Number(a.y),
        lon: Number(a.x),
      }));
    },
    async routes(start, goal) {
      const opt = OPTIONS.map((o) => o.id).join(':');
      const j = await call(`/map-direction/v1/driving?start=${start.lon},${start.lat}&goal=${goal.lon},${goal.lat}&option=${opt}`);
      if (j.code !== 0) return [];
      return OPTIONS.filter((o) => j.route && j.route[o.id]).map((o) => {
        const r = j.route[o.id][0];
        return {
          option: o.id,
          label: o.label,
          naviOption: o.navi,
          distanceM: r.summary.distance,
          durationS: Math.round(r.summary.duration / 1000),
          tollFare: r.summary.tollFare || 0,
          highways: highwayNames(r.section),
          path: simplify(r.path),
        };
      });
    },
  };
}

module.exports = { createNaver, OPTIONS, highwayNames, simplify, plain };
