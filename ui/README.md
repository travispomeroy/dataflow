# ui

Nx workspace (npm) for the Dataflow UI. One app: `apps/web` — an empty React + Vite
shell until M3, when the real views (dataflow list, builder canvas, run history) and
the UI libraries (MUI, React Flow) arrive.

All dependency versions are exact (no `^`/`~`); headline versions live in
`docs/versions.md`, everything else is pinned by this workspace's `package.json` +
lockfile. `engine-strict` + exact `engines` make a wrong Node/npm fail at install time.

```sh
npm ci          # lockfile-exact install
npm run build   # typecheck + vite build of every project
```

The M0 gate (`e2e/m0-gates.sh`, Stage 1) runs exactly those two commands.
