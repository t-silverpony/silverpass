'use strict';
// SQLite 저장소. 모든 쿼리는 prepared statement로만 실행한다(SQL 주입 방지).
const { DatabaseSync } = require('node:sqlite');
const crypto = require('node:crypto');

const COUPON_AMOUNT_WON = 3000;
const COUPON_MONTHLY_LIMIT = 2;
const PAIRING_TTL_MS = 5 * 60 * 1000;

const sha256 = (s) => crypto.createHash('sha256').update(s).digest('hex');
const newToken = (prefix) => `${prefix}_${crypto.randomBytes(32).toString('base64url')}`;
const newId = () => crypto.randomUUID();

/** 한국 시각 기준 yyyy-MM. 할인권 월 한도는 이 값으로 센다(이월 없음). */
function monthOf(ms) {
  const kst = new Date(ms + 9 * 3600 * 1000);
  return `${kst.getUTCFullYear()}-${String(kst.getUTCMonth() + 1).padStart(2, '0')}`;
}

class Store {
  constructor(path, { now = () => Date.now() } = {}) {
    this.now = now;
    this.db = new DatabaseSync(path);
    this.db.exec(`
      PRAGMA journal_mode = WAL;
      PRAGMA foreign_keys = ON;
      PRAGMA busy_timeout = 3000;
      CREATE TABLE IF NOT EXISTS accounts (
        id TEXT PRIMARY KEY,
        token_hash TEXT NOT NULL UNIQUE,
        name TEXT NOT NULL,
        quiet_start INTEGER, quiet_end INTEGER,
        avoid_highway INTEGER NOT NULL DEFAULT 0,
        version INTEGER NOT NULL DEFAULT 1,
        created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS vehicles (
        id TEXT PRIMARY KEY,
        token_hash TEXT NOT NULL UNIQUE,
        name TEXT NOT NULL,
        created_at INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS links (
        vehicle_id TEXT NOT NULL REFERENCES vehicles(id) ON DELETE CASCADE,
        account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
        created_at INTEGER NOT NULL,
        PRIMARY KEY (vehicle_id, account_id)
      );
      CREATE TABLE IF NOT EXISTS pairing_codes (
        code TEXT PRIMARY KEY,
        vehicle_id TEXT NOT NULL UNIQUE REFERENCES vehicles(id) ON DELETE CASCADE,
        expires_at INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS coupon_uses (
        request_id TEXT PRIMARY KEY,
        account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
        month TEXT NOT NULL,
        used_at INTEGER NOT NULL,
        amount_won INTEGER NOT NULL
      );
      CREATE INDEX IF NOT EXISTS coupon_by_account_month ON coupon_uses(account_id, month);
      CREATE TABLE IF NOT EXISTS plans (
        account_id TEXT PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
        plan_json TEXT NOT NULL,
        updated_at INTEGER NOT NULL
      );
    `);
  }

  close() { this.db.close(); }

  // 트랜잭션: 할인권 차감처럼 확인과 쓰기가 한 번에 일어나야 하는 곳에서 쓴다.
  tx(fn) {
    this.db.exec('BEGIN IMMEDIATE');
    try {
      const r = fn();
      this.db.exec('COMMIT');
      return r;
    } catch (e) {
      this.db.exec('ROLLBACK');
      throw e;
    }
  }

  count(table) {
    // table은 코드에서만 넘긴다(사용자 입력 아님).
    return this.db.prepare(`SELECT COUNT(*) AS n FROM ${table}`).get().n;
  }

  // ---------- 계정 ----------
  createAccount(name) {
    const token = newToken('spa');
    const id = newId();
    const t = this.now();
    this.db.prepare(`INSERT INTO accounts (id, token_hash, name, created_at, updated_at) VALUES (?, ?, ?, ?, ?)`)
      .run(id, sha256(token), name, t, t);
    return { token, account: this.accountView(id) };
  }

  accountByToken(token) {
    return this.db.prepare(`SELECT * FROM accounts WHERE token_hash = ?`).get(sha256(token)) || null;
  }

  accountView(id) {
    const a = this.db.prepare(`SELECT * FROM accounts WHERE id = ?`).get(id);
    if (!a) return null;
    return {
      id: a.id,
      name: a.name,
      conditions: {
        quietHours: a.quiet_start == null ? null : { start: a.quiet_start, end: a.quiet_end },
        avoidHighway: a.avoid_highway === 1,
      },
      version: a.version,
      updatedAt: a.updated_at,
      coupons: this.couponSummary(a.id),
      plan: this.plan(a.id),
    };
  }

  /** 낙관적 동시성: 클라이언트가 본 version과 다르면 덮어쓰지 않는다(다른 기기에서 먼저 고친 경우). */
  updateConditions(accountId, { quietHours, avoidHighway, version }) {
    return this.tx(() => {
      const a = this.db.prepare(`SELECT version FROM accounts WHERE id = ?`).get(accountId);
      if (!a) return { error: 'not_found' };
      if (a.version !== version) return { error: 'version_conflict', current: this.accountView(accountId) };
      this.db.prepare(`UPDATE accounts SET quiet_start = ?, quiet_end = ?, avoid_highway = ?, version = version + 1, updated_at = ? WHERE id = ?`)
        .run(quietHours ? quietHours.start : null, quietHours ? quietHours.end : null, avoidHighway ? 1 : 0, this.now(), accountId);
      return { account: this.accountView(accountId) };
    });
  }

  // ---------- 주행 계획(계정당 1개) ----------
  plan(accountId) {
    const r = this.db.prepare(`SELECT plan_json, updated_at FROM plans WHERE account_id = ?`).get(accountId);
    return r ? { ...JSON.parse(r.plan_json), updatedAt: r.updated_at } : null;
  }

  savePlan(accountId, plan) {
    this.db.prepare(`INSERT INTO plans (account_id, plan_json, updated_at) VALUES (?, ?, ?)
      ON CONFLICT(account_id) DO UPDATE SET plan_json = excluded.plan_json, updated_at = excluded.updated_at`)
      .run(accountId, JSON.stringify(plan), this.now());
    return this.plan(accountId);
  }

  deletePlan(accountId) {
    return this.db.prepare(`DELETE FROM plans WHERE account_id = ?`).run(accountId).changes > 0;
  }

  deleteAccount(accountId) {
    this.db.prepare(`DELETE FROM accounts WHERE id = ?`).run(accountId);
  }

  // ---------- 할인권 ----------
  couponSummary(accountId) {
    const month = monthOf(this.now());
    const uses = this.db.prepare(`SELECT request_id, used_at, amount_won FROM coupon_uses WHERE account_id = ? AND month = ? ORDER BY used_at DESC`)
      .all(accountId, month);
    return {
      month,
      amountWon: COUPON_AMOUNT_WON,
      monthlyLimit: COUPON_MONTHLY_LIMIT,
      remaining: Math.max(0, COUPON_MONTHLY_LIMIT - uses.length),
      uses: uses.map((u) => ({ requestId: u.request_id, usedAt: u.used_at, amountWon: u.amount_won })),
    };
  }

  /** 같은 requestId는 한 번만 차감한다. 다른 계정의 requestId를 재사용하면 거절한다. */
  redeemCoupon(accountId, requestId) {
    return this.tx(() => {
      const prev = this.db.prepare(`SELECT * FROM coupon_uses WHERE request_id = ?`).get(requestId);
      if (prev) {
        if (prev.account_id !== accountId) return { error: 'request_id_conflict' };
        return { alreadyDone: true, use: { requestId, usedAt: prev.used_at, amountWon: prev.amount_won }, coupons: this.couponSummary(accountId) };
      }
      const t = this.now();
      const month = monthOf(t);
      const used = this.db.prepare(`SELECT COUNT(*) AS n FROM coupon_uses WHERE account_id = ? AND month = ?`).get(accountId, month).n;
      if (used >= COUPON_MONTHLY_LIMIT) return { error: 'limit_reached', coupons: this.couponSummary(accountId) };
      this.db.prepare(`INSERT INTO coupon_uses (request_id, account_id, month, used_at, amount_won) VALUES (?, ?, ?, ?, ?)`)
        .run(requestId, accountId, month, t, COUPON_AMOUNT_WON);
      return { alreadyDone: false, use: { requestId, usedAt: t, amountWon: COUPON_AMOUNT_WON }, coupons: this.couponSummary(accountId) };
    });
  }

  // ---------- 차량·연결 ----------
  createVehicle(name) {
    const token = newToken('spv');
    const id = newId();
    this.db.prepare(`INSERT INTO vehicles (id, token_hash, name, created_at) VALUES (?, ?, ?, ?)`)
      .run(id, sha256(token), name, this.now());
    return { token, vehicle: { id, name } };
  }

  vehicleByToken(token) {
    return this.db.prepare(`SELECT * FROM vehicles WHERE token_hash = ?`).get(sha256(token)) || null;
  }

  /** 차량마다 유효한 연결번호는 하나뿐. 새로 만들면 이전 번호는 무효가 된다. */
  issuePairingCode(vehicleId) {
    return this.tx(() => {
      const t = this.now();
      this.db.prepare(`DELETE FROM pairing_codes WHERE vehicle_id = ? OR expires_at <= ?`).run(vehicleId, t);
      for (let i = 0; i < 20; i++) {
        const code = String(crypto.randomInt(0, 1_000_000)).padStart(6, '0');
        const taken = this.db.prepare(`SELECT 1 FROM pairing_codes WHERE code = ?`).get(code);
        if (taken) continue;
        const expiresAt = t + PAIRING_TTL_MS;
        this.db.prepare(`INSERT INTO pairing_codes (code, vehicle_id, expires_at) VALUES (?, ?, ?)`).run(code, vehicleId, expiresAt);
        return { code, expiresAt };
      }
      throw new Error('pairing code space exhausted');
    });
  }

  /**
   * 연결번호로 계정을 차량에 연결한다. 맞은 번호는 1회용으로 즉시 지운다.
   * 번호 추측은 server.js의 요청 제한(계정·IP·전체)으로 막는다: 6자리(100만 개) 중 유효 번호는 차량당 1개, 5분.
   */
  claimPairingCode(accountId, code) {
    return this.tx(() => {
      const t = this.now();
      const row = this.db.prepare(`SELECT * FROM pairing_codes WHERE code = ?`).get(code);
      if (!row) return { error: 'invalid_code' };
      if (row.expires_at <= t) {
        this.db.prepare(`DELETE FROM pairing_codes WHERE code = ?`).run(code);
        return { error: 'expired_code' };
      }
      this.db.prepare(`DELETE FROM pairing_codes WHERE code = ?`).run(code);
      this.db.prepare(`INSERT OR IGNORE INTO links (vehicle_id, account_id, created_at) VALUES (?, ?, ?)`).run(row.vehicle_id, accountId, t);
      const v = this.db.prepare(`SELECT id, name FROM vehicles WHERE id = ?`).get(row.vehicle_id);
      return { vehicle: { id: v.id, name: v.name } };
    });
  }

  linkedVehicles(accountId) {
    return this.db.prepare(`SELECT v.id, v.name, l.created_at AS linkedAt FROM links l JOIN vehicles v ON v.id = l.vehicle_id WHERE l.account_id = ? ORDER BY l.created_at`)
      .all(accountId);
  }

  linkedAccounts(vehicleId) {
    return this.db.prepare(`SELECT account_id FROM links WHERE vehicle_id = ? ORDER BY created_at`).all(vehicleId)
      .map((r) => this.accountView(r.account_id));
  }

  isLinked(vehicleId, accountId) {
    return !!this.db.prepare(`SELECT 1 FROM links WHERE vehicle_id = ? AND account_id = ?`).get(vehicleId, accountId);
  }

  unlink(vehicleId, accountId) {
    return this.db.prepare(`DELETE FROM links WHERE vehicle_id = ? AND account_id = ?`).run(vehicleId, accountId).changes > 0;
  }

  deleteVehicle(vehicleId) {
    this.db.prepare(`DELETE FROM vehicles WHERE id = ?`).run(vehicleId);
  }
}

module.exports = { Store, monthOf, COUPON_AMOUNT_WON, COUPON_MONTHLY_LIMIT, PAIRING_TTL_MS };
