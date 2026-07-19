import { apiFetch } from './api';

/** A Catalog entry as the read API serves it: logical names only, no physical. */
export interface CatalogEntry {
  id: string;
  name: string;
  description: string;
}

export function listSources() {
  return apiFetch<CatalogEntry[]>('/api/catalog/sources');
}
