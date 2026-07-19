import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query';
import { publishProblem } from './problem-snackbar';

/**
 * Server state lives here (spec #28): mutation → invalidation, no client-side
 * store. Every error — query or mutation — flows to the problem snackbar.
 */
export const queryClient = new QueryClient({
  queryCache: new QueryCache({ onError: publishProblem }),
  mutationCache: new MutationCache({ onError: publishProblem }),
});
