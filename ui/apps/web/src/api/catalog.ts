import { apiFetch } from './api';

/** A Catalog entry as the read API serves it: logical names only, no physical. */
export interface CatalogEntry {
  id: string;
  name: string;
  description: string;
}

/** Query keys, shared so components caching the same Catalog data never fork. */
export const catalogKeys = {
  sources: ['catalog', 'sources'] as const,
  destinations: ['catalog', 'destinations'] as const,
};

export function listSources() {
  return apiFetch<CatalogEntry[]>('/api/catalog/sources');
}

export function listDestinations() {
  return apiFetch<CatalogEntry[]>('/api/catalog/destinations');
}
