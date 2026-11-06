/**
 * The scripted faux model for tests and local runs (no model cost).
 *
 * The route answers by the last non-system message, like Pi Durable's example 23: a phone
 * request becomes a `phone_echo` tool call, a tool result becomes a text answer, and a
 * `[phonejob …]` follow-up is acknowledged. The real-model run script replaces this with
 * the Z.ai provider.
 */
import { writeFileSync } from "node:fs";
import type { MutableModels } from "@earendil-works/pi-ai";
import type { AssistantMessage, TranscriptContext } from "@earendil-works/pi-ai";
import {
	fauxAssistantMessage,
	fauxProvider,
	fauxText,
	fauxToolCall,
	type FauxProviderHandle,
	type FauxResponseStep,
} from "@earendil-works/pi-ai/providers/faux";

export type FauxPlan = {
	/** Every model request hangs forever (a mid-turn crash point for tests). */
	hang: boolean;
	/** Written (once) when a hanging request has actually arrived at the provider. */
	pendingMarker?: string;
	/**
	 * Models the faux provider offers (the first is the conversation's); default one model
	 * with Pi's default 128 000-token window. Tests use this for window and model-switch checks.
	 */
	models?: Array<{ id: string; contextWindow?: number }>;
};

type AnyMessage = TranscriptContext["messages"][number];

export function messageText(message: AnyMessage | undefined): string {
	const content = message?.content;
	const parts = typeof content === "string" ? [{ type: "text", text: content }] : (content ?? []);
	return parts
		.flatMap((part) => (part.type === "text" && typeof part.text === "string" ? [part.text] : []))
		.join("");
}

export const stewardFauxRoute: FauxResponseStep = (request: TranscriptContext): AssistantMessage => {
	const last = request.messages.findLast((message) => message.role !== "system");
	const text = messageText(last);
	if (last?.role === "toolResult") {
		return fauxAssistantMessage([fauxText("Your phone job is queued.")]);
	}
	if (last?.role === "user") {
		if (text.startsWith("[phonejob")) {
			return fauxAssistantMessage([fauxText("Thanks — the phone reported back.")]);
		}
		if (text.includes("check my phone")) {
			return fauxAssistantMessage([fauxToolCall("phone_echo", { payload: "storage overview" })], {
				stopReason: "toolUse",
			});
		}
	}
	return fauxAssistantMessage([fauxText("Hello!")]);
};

export function installStewardFauxPlan(models: MutableModels, plan: FauxPlan): FauxProviderHandle {
	const faux = fauxProvider({ tokensPerSecond: 4000, ...(plan.models === undefined ? {} : { models: plan.models }) });
	models.setProvider(faux.provider);
	let step: FauxResponseStep;
	if (plan.hang) {
		// The marker is written from inside the response factory: it proves a model request
		// is not merely started but actually pending at the provider when it appears.
		let announced = false;
		step = () => {
			if (!announced && plan.pendingMarker !== undefined) {
				announced = true;
				writeFileSync(plan.pendingMarker, new Date().toISOString(), "utf8");
			}
			return new Promise<AssistantMessage>(() => {});
		};
	} else {
		step = stewardFauxRoute;
	}
	// More responses than the script needs; each request takes the next one.
	faux.setResponses(Array.from({ length: 64 }, () => step));
	return faux;
}
