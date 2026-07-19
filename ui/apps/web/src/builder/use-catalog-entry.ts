import { useQuery } from '@tanstack/react-query';
import { catalogKeys, listDestinations, listSources } from '../api/catalog';
import type { CatalogEntry } from '../api/catalog';

/**
 * Resolves a Catalog id to its entry for display — the config carries only
 * ids (ADR-0005), so every surface showing a Source or Destination name goes
 * through this one lookup.
 */
export function useCatalogEntry(
  role: 'source' | 'destination',
  entryId: string,
): CatalogEntry | undefined {
  const entries = useQuery<CatalogEntry[]>({
    queryKey: role === 'source' ? catalogKeys.sources : catalogKeys.destinations,
    queryFn: role === 'source' ? listSources : listDestinations,
  });
  return entries.data?.find((entry) => entry.id === entryId);
}
