/**
 * The wiring of the orchestration integration (docs/protocol.md §8, §10): the core always
 * installs it. With orchestration disabled, the integration's external calls are no-ops
 * but its local event mirror still writes, so the integration is installed in every
 * configuration. A static import: a missing export fails the build, never silently.
 */
import { createOrchestrationIntegrations, type OrchestrationDeps } from "./orchestration/index.ts";
import type { OrchestrationConfig, StewardIntegrations } from "./integrations.ts";

export async function loadIntegrations(config: OrchestrationConfig, deps: OrchestrationDeps = {}): Promise<StewardIntegrations> {
	return createOrchestrationIntegrations(config, deps);
}
