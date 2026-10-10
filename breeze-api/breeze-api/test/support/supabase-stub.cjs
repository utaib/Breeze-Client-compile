'use strict';
/**
 * In-memory stand-in for the Supabase JS client, good enough to boot server.js
 * and exercise real routes in tests. It implements the PostgREST builder
 * surface the API actually uses: select/insert/update/upsert/delete with the
 * eq/neq/in/is/or/gt/lt/like filters, order, limit, range, single and
 * maybeSingle, plus rpc(). Everything is synchronous data in a plain object,
 * so a test can seed rows and then assert on what a route wrote.
 *
 * It is deliberately NOT a Postgres emulator. Anything it cannot answer
 * returns a clear error rather than silently succeeding, so a test can never
 * pass because the stub invented a result.
 */

const OPS = {
  eq: (a, b) => normalize(a) === normalize(b),
  neq: (a, b) => normalize(a) !== normalize(b),
  gt: (a, b) => a > b,
  gte: (a, b) => a >= b,
  lt: (a, b) => a < b,
  lte: (a, b) => a <= b,
  like: (a, b) => matchLike(a, b, false),
  ilike: (a, b) => matchLike(a, b, true),
  is: (a, b) => (b === null || b === 'null' ? a === null || a === undefined : a === b),
  in: (a, b) => (Array.isArray(b) ? b.some((v) => normalize(v) === normalize(a)) : false),
  cs: (a, b) => {
    if (Array.isArray(a)) return (Array.isArray(b) ? b : [b]).every((v) => a.includes(v));
    // jsonb @> object: every key asked for is present with the same value.
    if (a && typeof a === 'object') {
      const want = typeof b === 'string' ? JSON.parse(b) : b;
      if (!want || typeof want !== 'object' || Array.isArray(want)) return false;
      return Object.entries(want).every(([k, v]) => JSON.stringify(a[k]) === JSON.stringify(v));
    }
    return false;
  },
};

// PostgREST filters travel as strings and Postgres casts them, so an id of 1
// matches a filter of '1' from a URL parameter.
function normalize(v) {
  if (typeof v === 'number') return String(v);
  return typeof v === 'string' ? v.toLowerCase() : v;
}

/**
 * LIKE the way Postgres reads it: "%" is any run, "_" is one character, and a
 * backslash makes the next character literal. The escape matters, because a
 * username may contain "_" and the API escapes it before searching; a stub that
 * ignored the escape would pass a test that production fails.
 */
function matchLike(value, pattern, insensitive) {
  if (value === null || value === undefined) return false;
  const escapeRe = (c) => c.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const p = String(pattern);
  let rx = '^';
  for (let i = 0; i < p.length; i++) {
    const ch = p[i];
    if (ch === '\\' && i + 1 < p.length) rx += escapeRe(p[++i]);
    else if (ch === '%') rx += '.*';
    else if (ch === '_') rx += '.';
    else rx += escapeRe(ch);
  }
  return new RegExp(rx + '$', insensitive ? 'i' : '').test(String(value));
}

/** Split on commas that are not inside a nested group. */
function splitTop(expr) {
  const parts = [];
  let depth = 0;
  let current = '';
  for (const ch of expr) {
    if (ch === '(') depth++;
    if (ch === ')') depth--;
    if (ch === ',' && depth === 0) {
      parts.push(current);
      current = '';
      continue;
    }
    current += ch;
  }
  if (current) parts.push(current);
  return parts;
}

/**
 * A PostgREST filter expression as a tree.
 *
 * `or()` may contain `and()` groups, which is how the API asks for "either
 * direction of this friendship":
 *   or=(and(requester_uuid.eq.A,addressee_uuid.eq.B),and(requester_uuid.eq.B,addressee_uuid.eq.A))
 * Splitting on commas alone turned each group into the nonsense column "and(a",
 * which matched nothing and made a duplicate friendship look like a new one.
 */
function parseFilterExpr(expr) {
  const parts = splitTop(String(expr));
  if (parts.length > 1) return { type: 'or', children: parts.map(parseFilterExpr) };
  const part = parts[0].trim();
  const group = /^(and|or)\((.*)\)$/s.exec(part);
  if (group) {
    return { type: group[1], children: splitTop(group[2]).map(parseFilterExpr) };
  }
  const [column, op, ...rest] = part.split('.');
  let value = rest.join('.');
  if (op === 'in') value = value.replace(/^\(|\)$/g, '').split(',').map((v) => v.replace(/^"|"$/g, ''));
  return { type: 'cmp', column, op, value };
}

function evalFilter(node, row) {
  if (node.type === 'and') return node.children.every((c) => evalFilter(c, row));
  if (node.type === 'or') return node.children.some((c) => evalFilter(c, row));
  const fn = OPS[node.op];
  if (!fn) throw new Error(`supabase-stub: unsupported filter "${node.op}" on ${node.column}`);
  return fn(row[node.column], node.value);
}

function parseOrFilter(expr) {
  const node = parseFilterExpr(expr);
  // A single top-level term is still an "or" of one.
  return node.type === 'or' ? node : { type: 'or', children: [node] };
}

class Result {
  constructor(data, error, count) {
    this.data = data === undefined ? null : data;
    this.error = error || null;
    this.count = count === undefined ? null : count;
    this.status = error ? 400 : 200;
  }
}

class QueryBuilder {
  constructor(db, table) {
    this.db = db;
    this.table = table;
    this.filters = [];
    this.orFilters = null;
    this.action = 'select';
    this.payload = null;
    this.selectedColumns = '*';
    this.orderBy = null;
    this.limitCount = null;
    this.rangeFrom = null;
    this.rangeTo = null;
    this.singleMode = null;
    this.wantCount = false;
    this.upsertConflict = null;
  }

  rows() {
    if (!this.db.tables[this.table]) this.db.tables[this.table] = [];
    return this.db.tables[this.table];
  }

  select(columns, options) {
    if (this.action === 'select') this.action = 'select';
    this.selectedColumns = columns || '*';
    if (options && options.count) this.wantCount = true;
    return this;
  }

  insert(payload, options) {
    this.action = 'insert';
    this.payload = Array.isArray(payload) ? payload : [payload];
    this.insertOptions = options || {};
    return this;
  }

  upsert(payload, options) {
    this.action = 'upsert';
    this.payload = Array.isArray(payload) ? payload : [payload];
    this.upsertConflict = (options && options.onConflict ? options.onConflict : 'id').split(',').map((c) => c.trim());
    return this;
  }

  update(payload) {
    this.action = 'update';
    this.payload = payload;
    return this;
  }

  delete() {
    this.action = 'delete';
    return this;
  }

  order(column, options) {
    this.orderBy = { column, ascending: !options || options.ascending !== false };
    return this;
  }

  limit(n) {
    this.limitCount = n;
    return this;
  }

  range(from, to) {
    this.rangeFrom = from;
    this.rangeTo = to;
    return this;
  }

  single() {
    this.singleMode = 'single';
    return this;
  }

  maybeSingle() {
    this.singleMode = 'maybe';
    return this;
  }

  or(expression) {
    this.orFilters = parseOrFilter(expression);
    return this;
  }

  not(column, op, value) {
    this.filters.push({ column, op, value, negate: true });
    return this;
  }

  filter(column, op, value) {
    this.filters.push({ column, op, value });
    return this;
  }

  matches(row) {
    for (const f of this.filters) {
      const fn = OPS[f.op];
      if (!fn) throw new Error(`supabase-stub: unsupported filter "${f.op}" on ${this.table}.${f.column}`);
      const hit = fn(row[f.column], f.value);
      if (f.negate ? hit : !hit) return false;
    }
    if (this.orFilters && !evalFilter(this.orFilters, row)) return false;
    return true;
  }

  run() {
    const rows = this.rows();
    if (this.action === 'insert' || this.action === 'upsert') {
      const written = [];
      for (const item of this.payload) {
        const record = { ...item };
        if (this.action === 'upsert') {
          const existing = rows.find((r) => this.upsertConflict.every((k) => normalize(r[k]) === normalize(record[k])));
          if (existing) {
            Object.assign(existing, record);
            written.push(existing);
            continue;
          }
        }
        if (record.id === undefined && this.db.autoId) record.id = this.db.nextId(this.table);
        if (record.created_at === undefined) record.created_at = this.db.now;
        rows.push(record);
        written.push(record);
      }
      return this.shaped(written, written.length);
    }

    const matched = rows.filter((r) => this.matches(r));

    if (this.action === 'update') {
      for (const row of matched) Object.assign(row, this.payload);
      return this.shaped(matched, matched.length);
    }
    if (this.action === 'delete') {
      for (const row of matched) rows.splice(rows.indexOf(row), 1);
      return this.shaped(matched, matched.length);
    }

    let out = matched.map((r) => ({ ...r }));
    if (this.orderBy) {
      const { column, ascending } = this.orderBy;
      out.sort((a, b) => {
        if (a[column] === b[column]) return 0;
        const cmp = a[column] > b[column] ? 1 : -1;
        return ascending ? cmp : -cmp;
      });
    }
    if (this.rangeFrom !== null) out = out.slice(this.rangeFrom, this.rangeTo + 1);
    if (this.limitCount !== null) out = out.slice(0, this.limitCount);

    return this.shaped(out.map((row) => this.embed(row)), matched.length);
  }

  /**
   * PostgREST resource embedding, enough for this API: `alias:table(cols)` or
   * `table(cols)` in a select attaches the row of `table` whose id matches this
   * row's `<singular>_id` column (cosmetics -> cosmetic_id, capes -> cape_id).
   * Without it, a route reading `row.cosmetic` sees undefined here but an
   * object in production, and a test cannot tell the two apart.
   */
  embed(row) {
    const spec = typeof this.selectedColumns === 'string' ? this.selectedColumns : '';
    const re = /(?:(\w+):)?(\w+)\(([^()]*)\)/g;
    let m;
    const out = { ...row };
    while ((m = re.exec(spec))) {
      const [, alias, table] = m;
      const fk = `${table.replace(/s$/, '')}_id`;
      if (!(fk in row)) continue;
      const target = (this.db.tables[table] || []).find((r) => normalize(r.id) === normalize(row[fk]));
      out[alias || table] = target ? { ...target } : null;
    }
    return out;
  }

  /**
   * Apply .single() / .maybeSingle(). PostgREST honours them on writes too:
   * insert(...).select().single() returns the row object, not an array.
   */
  shaped(out, count) {
    if (this.singleMode === 'single') {
      if (out.length !== 1) return new Result(null, { message: 'JSON object requested, multiple (or no) rows returned', code: 'PGRST116' });
      return new Result(out[0], null, 1);
    }
    if (this.singleMode === 'maybe') {
      return new Result(out.length ? out[0] : null, null, out.length);
    }
    return new Result(out, null, count);
  }

  then(resolve, reject) {
    let result;
    try {
      result = this.run();
    } catch (error) {
      result = new Result(null, { message: error.message });
    }
    return this.db.answer(result).then(resolve, reject);
  }

  catch(fn) {
    return this.then((r) => r).catch(fn);
  }
}

// PostgREST exposes one chainable method per filter operator. Generating them
// from OPS keeps the two in step: adding an operator above makes it callable
// here automatically, and a builder missing a method would otherwise throw
// mid-route and look like a server bug rather than a gap in this stub.
for (const op of Object.keys(OPS)) {
  QueryBuilder.prototype[op] = function addFilter(column, value) {
    this.filters.push({ column, op, value });
    return this;
  };
}
QueryBuilder.prototype.contains = QueryBuilder.prototype.cs;
QueryBuilder.prototype.match = function matchAll(criteria) {
  for (const [column, value] of Object.entries(criteria || {})) {
    this.filters.push({ column, op: 'eq', value });
  }
  return this;
};

class StubDatabase {
  constructor(seed) {
    this.tables = {};
    this.counters = {};
    this.now = '2026-01-01T00:00:00.000Z';
    this.autoId = true;
    this.rpcHandlers = {};
    // Real Supabase answers over the network, so two requests' queries
    // interleave. With BREEZE_TEST_DB_LATENCY_MS set, each query still runs
    // at once (atomically, as in Postgres) but its answer arrives later, which
    // lets a test see races the instant stub would hide.
    this.latencyMs = Number(process.env.BREEZE_TEST_DB_LATENCY_MS || 0);
    for (const [table, rows] of Object.entries(seed || {})) this.tables[table] = rows.map((r) => ({ ...r }));
  }

  answer(result) {
    if (!this.latencyMs) return Promise.resolve(result);
    return new Promise((resolve) => setTimeout(() => resolve(result), this.latencyMs));
  }

  nextId(table) {
    this.counters[table] = (this.counters[table] || 0) + 1;
    return this.counters[table];
  }

  snapshot() {
    return JSON.parse(JSON.stringify(this.tables));
  }
}

function createStubClient(seed) {
  const db = new StubDatabase(seed);
  const client = {
    __db: db,
    from(table) {
      return new QueryBuilder(db, table);
    },
    rpc(name, args) {
      const handler = db.rpcHandlers[name];
      if (!handler) return Promise.resolve(new Result(null, { message: `supabase-stub: rpc "${name}" is not stubbed` }));
      return db.answer(new Result(handler(args, db)));
    },
    storage: {
      from() {
        throw new Error('supabase-stub: storage is not used by this API (assets live on disk)');
      },
    },
    auth: {
      getUser() {
        return Promise.resolve(new Result(null, { message: 'supabase-stub: auth is not used by this API' }));
      },
    },
  };
  return client;
}

module.exports = { createStubClient, StubDatabase, parseOrFilter };
