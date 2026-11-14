// The local display name's rule, for the preview fixture only: in Minecraft
// Java decides (common/src/main/java/dev/breeze/names/LocalName.java). Both
// are checked against contract/local-name.json, so they say the same.

export const LOCAL_NAME_MAX = 24

/** The name to keep, or an Error whose message says what to change. */
export function cleanLocalName(raw: string): string {
  let out = ''
  let space = false
  for (const ch of raw) {
    if (/\s/u.test(ch)) {
      space = out.length > 0
      continue
    }
    if (ch === '§') throw new Error('Formatting codes (§) are not allowed.')
    if (ch.codePointAt(0)! > 0xffff) {
      throw new Error('Minecraft cannot draw one of those characters (emoji and other symbols outside its font).')
    }
    if (/[\p{Cc}\p{Cf}\p{Cs}\p{Cn}\p{Zl}\p{Zp}]/u.test(ch)) throw new Error('Use letters, numbers, spaces and ordinary symbols only.')
    if (/\p{Co}/u.test(ch)) throw new Error("That character is reserved for Breeze's own icons.")
    if (space) out += ' '
    space = false
    out += ch
  }
  if (!out) throw new Error('Type a name.')
  if ([...out].length > LOCAL_NAME_MAX) throw new Error(`Use at most ${LOCAL_NAME_MAX} characters.`)
  return out
}
