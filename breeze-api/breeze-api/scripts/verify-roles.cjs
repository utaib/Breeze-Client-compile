/**
 * Role, badge and tag colour rules, checked against the real server.js.
 *
 * The functions are extracted from the shipped source rather than reimplemented
 * here, so this fails when the behaviour changes rather than when a copy of it
 * drifts. Run with: npm run verify:roles
 *
 * The colour cases are the reason this exists. A creator may recolour their
 * Creator tag, and nothing else: not the badge, not another tag, and not into
 * a colour that is not on the list, which is how a stale value from the retired
 * custom tag editor would otherwise come back to life.
 */
const fs = require('fs');
const path = require('path');
// Read the shipped server rather than a copy, so this cannot pass against code
// that is no longer what runs in production.
const src = fs.readFileSync(path.join(__dirname, '..', 'server.js'), 'utf8').replace(/\r\n/g, '\n');

function grab(name) {
  const start = src.indexOf('function ' + name + '(');
  if (start < 0) throw new Error('not found: ' + name);
  // Walk braces from the first { after the signature.
  let i = src.indexOf('{', start), depth = 0;
  for (; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') { depth--; if (depth === 0) return src.slice(start, i + 1); }
  }
  throw new Error('unbalanced: ' + name);
}

/** A top-level `const NAME = ...;` statement, taken from the source verbatim. */
function grabConst(name) {
  const start = src.indexOf('const ' + name + ' =');
  if (start < 0) throw new Error('not found: ' + name);
  const end = src.indexOf(';\n', start);
  return src.slice(start, end + 1);
}

const names = ['tagsForUser', 'roleBadgeForUser', 'roleConfig', 'displayTagForUser',
  'normalizeHex', 'creatorColorOption', 'tagColorFor', 'canChooseTagColor'];
const code = [grabConst('CREATOR_TAG_COLORS'), grabConst('COLOR_CHOICE_TAG'), ...names.map(grab)].join('\n\n');
const T = {};
new Function('out', code + `
  Object.assign(out, { ${names.join(', ')}, CREATOR_TAG_COLORS });
`)(T);

// The tag table as schema.sql leaves it after the 1.0.22 section.
const tags = [
  { id: 'o', slug: 'owner',     name: 'Owner',     color: '#FF5555', priority_weight: 100, auto_role: 'owner',     is_role_badge: true },
  { id: 'd', slug: 'developer', name: 'Developer', color: '#A56EFF', priority_weight: 90,  auto_role: 'developer', is_role_badge: true },
  { id: 'a', slug: 'admin',     name: 'Admin',     color: '#800080', priority_weight: 80,  auto_role: 'admin',     is_role_badge: true },
  { id: 'c', slug: 'creator',   name: 'Creator',   color: '#FFD23F', priority_weight: 50,  auto_role: 'creator',   is_role_badge: true },
  { id: 'n', slug: 'donator',   name: 'Donator',   color: '#FFD700', priority_weight: 30,  auto_role: null,        is_role_badge: true },
  { id: 'e', slug: 'event',     name: 'Event',     color: '#00FF88', priority_weight: 40,  auto_role: null,        is_role_badge: false },
  { id: 'b', slug: 'breeze',    name: 'Breeze',    color: '#55C8FF', priority_weight: 10,  auto_role: null,        is_role_badge: true },
];

const data = (grants) => ({ tags, userTags: new Map(Object.entries(grants || {})) });

let fails = 0;
function check(label, got, want) {
  const okk = JSON.stringify(got) === JSON.stringify(want);
  if (!okk) fails++;
  console.log((okk ? 'ok   ' : 'FAIL ') + label + (okk ? '' : `\n       got  ${JSON.stringify(got)}\n       want ${JSON.stringify(want)}`));
}

// The central claim: the badge ignores what the user equipped.
const admin = { uuid: 'u1', role: 'admin', equipped_tag_id: 'n' };
const d1 = data({ u1: ['n'] });
check('admin equipping Donator still displays Donator',
  T.displayTagForUser(admin, d1).slug, 'donator');
check('...but their BADGE is still Admin',
  T.roleBadgeForUser(admin, d1).slug, 'admin');

// A demoted user must not keep a staff badge.
const demoted = { uuid: 'u2', role: 'user', equipped_tag_id: 'a' };
check('demoted user cannot display Admin',
  T.displayTagForUser(demoted, data({})).slug, 'breeze');
check('demoted user badge falls to Breeze',
  T.roleBadgeForUser(demoted, data({})).slug, 'breeze');

// The hierarchy: owner > developer > creator > user.
const badgeOf = (role) => T.roleBadgeForUser({ uuid: 'h-' + role, role }, data({})).slug;
check('owner outranks everything', badgeOf('owner'), 'owner');
check('developer badge for a developer', badgeOf('developer'), 'developer');
check('creator badge for a creator', badgeOf('creator'), 'creator');
check('a plain user is Breeze', badgeOf('user'), 'breeze');
const both = { uuid: 'h-2', role: 'developer', equipped_tag_id: null };
check('a developer who is also granted Creator still shows Developer',
  T.displayTagForUser(both, data({ 'h-2': ['c'] })).slug, 'developer');

// A non-badge tag must never become a badge.
const eventy = { uuid: 'u4', role: 'user', equipped_tag_id: 'e' };
const d4 = data({ u4: ['e'] });
check('non-badge Event tag displays', T.displayTagForUser(eventy, d4).slug, 'event');
check('...but is not a badge', T.roleBadgeForUser(eventy, d4).slug, 'breeze');

// The role table the mod renders from excludes non-badges.
check('roleConfig omits non-badge tags',
  Object.keys(T.roleConfig(data({}))).sort(),
  ['admin', 'breeze', 'creator', 'developer', 'donator', 'owner']);
check('roleConfig carries the role colours',
  ['breeze', 'creator', 'developer', 'owner'].map((r) => T.roleConfig(data({}))[r].color),
  ['#55C8FF', '#FFD23F', '#A56EFF', '#FF5555']);

// Creator tag colour.
const creatorTag = tags.find((t) => t.slug === 'creator');
const ownerTag = tags.find((t) => t.slug === 'owner');
const creator = { uuid: 'u5', role: 'creator', custom_tag_color: '#ff78c8' };
check('a creator may hold the colour choice', T.canChooseTagColor(creator, data({})), true);
check('their chosen colour applies to the Creator tag',
  T.tagColorFor(creator, creatorTag), '#FF78C8');
check('with nothing chosen the Creator tag is yellow',
  T.tagColorFor({ ...creator, custom_tag_color: null }, creatorTag), '#FFD23F');

const plain = { uuid: 'u6', role: 'user', custom_tag_color: '#FF78C8' };
check('a normal user has no colour choice', T.canChooseTagColor(plain, data({})), false);
const ownerUser = { uuid: 'u7', role: 'owner', custom_tag_color: '#55C8FF' };
check('an owner has no colour choice either', T.canChooseTagColor(ownerUser, data({})), false);
check('a stored colour never recolours another tag',
  T.tagColorFor(ownerUser, ownerTag), '#FF5555');

check('red is not an option, so staff colours stay staff',
  T.creatorColorOption('#FF5555'), null);
check('nor purple', T.creatorColorOption('#A56EFF'), null);
const stale = { uuid: 'u8', role: 'creator', custom_tag_color: '#123456' };
check('a leftover colour from the old custom tag editor is ignored',
  T.tagColorFor(stale, creatorTag), '#FFD23F');
check('malformed input is refused', T.creatorColorOption('pink'), null);
check('every option is a wind charge colour',
  T.CREATOR_TAG_COLORS.map((o) => o.color), ['#55C8FF', '#FF78C8', '#C8EBF5']);

// The old custom tag helpers are gone from the shipped server.
for (const gone of ['customTagFor', 'sanitizeCustomTag', 'collidesWithRole']) {
  check(`${gone} is removed`, src.includes('function ' + gone + '('), false);
}

console.log(fails ? `\n${fails} FAILED` : '\nall pass');
process.exit(fails ? 1 : 0);
