/**
 * In-process wake-ups for the live session channel. The durable record is the session
 * document; this hub only lets a waiting tool call and a long-polling request notice a
 * change without polling storage. Nothing here survives a restart, by design: a live
 * session never outlives its process (A10.3: a step is never resent after a crash).
 */

export type StepWaitResult = "answered" | "timeout" | "ended";

type StepWaiter = { conversationId: number; settle: (result: StepWaitResult) => void };

const stepWaiters = new Map<string, StepWaiter>();
const pollWaiters = new Map<string, Set<() => void>>();

export type AskWaitResult = "answered" | "timeout" | "ended";

type AskWaiter = { conversationId: number; settle: (result: AskWaitResult) => void };

const askWaiters = new Map<string, AskWaiter>();

export type PendingStepWait = {
	wait(timeoutMs: number, signal?: AbortSignal): Promise<StepWaitResult>;
	cancel(): void;
};

/** Register interest in a step BEFORE it is queued, so an answer can never slip past. */
export function expectStepAnswer(conversationId: number, stepId: string): PendingStepWait {
	let settle: (result: StepWaitResult) => void = () => undefined;
	const promise = new Promise<StepWaitResult>((resolve) => {
		settle = resolve;
	});
	stepWaiters.set(stepId, { conversationId, settle: (result) => settle(result) });
	return {
		wait(timeoutMs, signal) {
			let timer: ReturnType<typeof setTimeout> | undefined;
			let onAbort: (() => void) | undefined;
			return new Promise<StepWaitResult>((resolve) => {
				timer = setTimeout(() => resolve("timeout"), timeoutMs);
				onAbort = (): void => resolve("ended");
				signal?.addEventListener("abort", onAbort, { once: true });
				void promise.then((result) => resolve(result));
			}).finally(() => {
				if (timer !== undefined) clearTimeout(timer);
				if (onAbort !== undefined) signal?.removeEventListener("abort", onAbort);
				stepWaiters.delete(stepId);
			});
		},
		cancel() {
			stepWaiters.delete(stepId);
		},
	};
}

/** The phone answered this step. */
export function stepAnswered(stepId: string): void {
	stepWaiters.get(stepId)?.settle("answered");
}

/**
 * A13.4: register interest in the owner's reply to a `screen_ask` BEFORE the ask is
 * queued, so a reply can never slip past. Same contract as `expectStepAnswer`.
 */
export function expectAskAnswer(conversationId: number, askId: string): { wait(timeoutMs: number, signal?: AbortSignal): Promise<AskWaitResult>; cancel(): void } {
	let settle: (result: AskWaitResult) => void = () => undefined;
	const promise = new Promise<AskWaitResult>((resolve) => {
		settle = resolve;
	});
	askWaiters.set(askId, { conversationId, settle });
	return {
		wait(timeoutMs, signal) {
			let timer: ReturnType<typeof setTimeout> | undefined;
			let onAbort: (() => void) | undefined;
			return new Promise<AskWaitResult>((resolve) => {
				timer = setTimeout(() => resolve("timeout"), timeoutMs);
				onAbort = (): void => resolve("ended");
				signal?.addEventListener("abort", onAbort, { once: true });
				void promise.then((result) => resolve(result));
			}).finally(() => {
				if (timer !== undefined) clearTimeout(timer);
				if (onAbort !== undefined) signal?.removeEventListener("abort", onAbort);
				askWaiters.delete(askId);
			});
		},
		cancel() {
			askWaiters.delete(askId);
		},
	};
}

/** The owner's chat reply answered this ask. */
export function askAnswered(askId: string): void {
	askWaiters.get(askId)?.settle("answered");
}

/** The session behind this conversation is over: release every waiting tool call. */
export function conversationEnded(conversationId: number): void {
	for (const waiter of stepWaiters.values()) {
		if (waiter.conversationId === conversationId) waiter.settle("ended");
	}
	for (const waiter of askWaiters.values()) {
		if (waiter.conversationId === conversationId) waiter.settle("ended");
	}
}

/** Release every waiter (a restart sweep or a test reset). */
export function endAllWaiters(): void {
	for (const waiter of stepWaiters.values()) waiter.settle("ended");
	for (const waiter of askWaiters.values()) waiter.settle("ended");
	for (const set of pollWaiters.values()) for (const wake of set) wake();
}

/** A long-poll registers before it reads, and is woken when a step or an end arrives. */
export function waitForPoll(sessionId: string, timeoutMs: number, signal?: AbortSignal): { promise: Promise<void>; dispose: () => void } {
	let wake: () => void = () => undefined;
	const promise = new Promise<void>((resolve) => {
		wake = resolve;
	});
	const set = pollWaiters.get(sessionId) ?? new Set();
	pollWaiters.set(sessionId, set);
	set.add(wake);
	const timer = setTimeout(wake, timeoutMs);
	const onAbort = (): void => wake();
	signal?.addEventListener("abort", onAbort, { once: true });
	return {
		promise,
		dispose: () => {
			clearTimeout(timer);
			signal?.removeEventListener("abort", onAbort);
			set.delete(wake);
			if (set.size === 0) pollWaiters.delete(sessionId);
		},
	};
}

export function wakePollers(sessionId: string): void {
	const set = pollWaiters.get(sessionId);
	if (set === undefined) return;
	for (const wake of [...set]) wake();
}
