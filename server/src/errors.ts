/**
 * The one error shape of the API (docs/protocol.md §1):
 * `{"error": {"code": "UPPER_SNAKE", "message": "human text", "retryAfterSeconds": 30}}`.
 * `retryAfterSeconds` is present only with 429 and 503, which also send `Retry-After`.
 */
export class ApiError extends Error {
	readonly status: number;
	readonly code: string;
	readonly retryAfterSeconds?: number;
	/** A13.2: extra fields beside `error` (for example the current status of a request). */
	readonly extra?: Record<string, unknown>;

	constructor(status: number, code: string, message: string, retryAfterSeconds?: number, extra?: Record<string, unknown>) {
		super(message);
		this.name = "ApiError";
		this.status = status;
		this.code = code;
		if (retryAfterSeconds !== undefined) this.retryAfterSeconds = retryAfterSeconds;
		if (extra !== undefined) this.extra = extra;
	}

	body(): { error: { code: string; message: string; retryAfterSeconds?: number }; [key: string]: unknown } {
		const error: { code: string; message: string; retryAfterSeconds?: number } = {
			code: this.code,
			message: this.message,
		};
		if (this.retryAfterSeconds !== undefined) error.retryAfterSeconds = this.retryAfterSeconds;
		const out: { error: typeof error; [key: string]: unknown } = { error };
		if (this.extra !== undefined) Object.assign(out, this.extra);
		return out;
	}
}

export function badRequest(message: string): ApiError {
	return new ApiError(400, "BAD_REQUEST", message);
}

export function unauthorized(message = "missing or invalid device token"): ApiError {
	return new ApiError(401, "UNAUTHORIZED", message);
}

export function tokenRevoked(): ApiError {
	return new ApiError(401, "TOKEN_REVOKED", "this device token has been revoked");
}

export function notFound(message = "not found"): ApiError {
	return new ApiError(404, "NOT_FOUND", message);
}

export function rateLimited(retryAfterSeconds: number): ApiError {
	return new ApiError(429, "RATE_LIMITED", "too many attempts; try again later", retryAfterSeconds);
}

export function notImplemented(message: string): ApiError {
	return new ApiError(501, "NOT_IMPLEMENTED", message);
}

export function conflict(code: string, message: string): ApiError {
	return new ApiError(409, code, message);
}

/** A conflict whose error body also carries plain fields (A13.2: the current status). */
export function conflictWith(code: string, message: string, extra: Record<string, unknown>): ApiError {
	return new ApiError(409, code, message, undefined, extra);
}
