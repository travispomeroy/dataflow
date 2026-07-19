const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/**
 * Coarse "how long ago" for the last-run chip: just now → minutes → hours →
 * days. An instant slightly in the future (clock skew between browser and
 * control plane) clamps to "just now" rather than inventing negative ages.
 */
export function relativeTime(iso: string, now: Date): string {
  const elapsed = now.getTime() - new Date(iso).getTime();
  if (elapsed < MINUTE) {
    return 'just now';
  }
  if (elapsed < HOUR) {
    return ago(Math.floor(elapsed / MINUTE), 'minute');
  }
  if (elapsed < DAY) {
    return ago(Math.floor(elapsed / HOUR), 'hour');
  }
  return ago(Math.floor(elapsed / DAY), 'day');
}

function ago(count: number, unit: string) {
  return `${count} ${unit}${count === 1 ? '' : 's'} ago`;
}
