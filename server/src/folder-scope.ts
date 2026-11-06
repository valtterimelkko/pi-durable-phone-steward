/**
 * Protocol A6: folder scopes are subtrees (docs/protocol.md A6). A folder
 * entry matches the items directly in it and in every subfolder; the trailing `/` is
 * optional because both sides are normalised before the prefix comparison. Case is
 * compared as stored. Exact matching was the MVP behaviour and it returned no candidates
 * in the first live weekly check, leaving WhatsApp and Telegram media outside the photo
 * rule.
 */

/** Normalise one folder scope or item folder: no leading slash, exactly one trailing slash. */
export function normaliseFolderScope(folder: string): string {
	const withoutLeading = folder.replace(/^\/+/, "");
	return withoutLeading.endsWith("/") ? withoutLeading : `${withoutLeading}/`;
}

/** True when the item's folder is inside any of the scopes (subtree match, A6). */
export function inFolderScope(relativePath: string, scopes: readonly string[]): boolean {
	const item = normaliseFolderScope(relativePath);
	return scopes.some((scope) => item.startsWith(normaliseFolderScope(scope)));
}

/** Normalise a rules folder list in place for storage (PUT /v1/rules accepts both shapes). */
export function normaliseFolderScopes(folders: readonly string[]): string[] {
	return folders.map((folder) => normaliseFolderScope(folder));
}
