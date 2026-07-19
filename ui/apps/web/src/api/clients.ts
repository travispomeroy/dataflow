import { apiFetch } from './api';

/**
 * Client reference data (`/api/clients`) — what the filter multi-select
 * offers. Reference data, not a Catalog entry, mirroring the control plane's
 * own seam split.
 */
export interface Client {
  id: string;
  name: string;
  advisorGroup: string;
}

export const clientsKey = ['clients'] as const;

export function listClients() {
  return apiFetch<Client[]>('/api/clients');
}
