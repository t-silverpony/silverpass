'use strict';
// 고정 창 요청 제한. 키 수에 상한을 둬서 키를 마구 만들어 메모리를 채우는 공격을 막는다.
class RateLimiter {
  constructor({ now = () => Date.now(), maxKeys = 50_000 } = {}) {
    this.now = now;
    this.maxKeys = maxKeys;
    this.buckets = new Map();
  }

  hit(key, windowMs, max) {
    const t = this.now();
    let b = this.buckets.get(key);
    if (!b || b.reset <= t) {
      if (!b && this.buckets.size >= this.maxKeys) this.sweep(t);
      if (!b && this.buckets.size >= this.maxKeys) return false; // 가득 차면 새 키는 거절(안전한 쪽)
      b = { count: 0, reset: t + windowMs };
      this.buckets.set(key, b);
    }
    b.count += 1;
    return b.count <= max;
  }

  sweep(t) {
    for (const [k, b] of this.buckets) if (b.reset <= t) this.buckets.delete(k);
  }
}

module.exports = { RateLimiter };
