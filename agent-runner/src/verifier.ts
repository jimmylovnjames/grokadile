import type { Verifier } from './ports';
import type { Observation } from './types';

/**
 * Default verifier: progress == the screen changed. A cheap, deterministic
 * signal (no LLM) that's enough to distinguish "the action did something" from
 * "nothing happened, try recovery". Swap in a goal-aware verifier per skill.
 */
export class ScreenChangeVerifier implements Verifier {
  verify(before: Observation, after: Observation): { progressed: boolean } {
    return { progressed: before.hash !== after.hash };
  }
}
