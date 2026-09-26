/** Matches server PublicConfigDto — the narrow set of config values the client is
 *  allowed to see. googleMapsEmbedKey is null when the server has no key configured
 *  (rather than a crash or an empty-string key that'd render a broken embed). */
export interface PublicConfig {
  googleMapsEmbedKey: string | null;
}
