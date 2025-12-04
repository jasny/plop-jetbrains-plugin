// Simple helper script to list Plop generators for a given project directory.
//
// Usage: node list-generators.js <projectDir>
// - Tries to load a plopfile from the given project directory using @plopjs/core (or plop).
// - Prints a JSON array to stdout: [{ name, description }, ...]
// - On any error, prints [] and exits with code 0 (soft fail).

/* eslint-disable no-console */

const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');

function findPlopfile(projectDir) {
  const candidates = [
    'plopfile.js',
    'plopfile.cjs',
    // We intentionally skip .mjs/.ts here to avoid ESM/ts-node complications in this simple helper.
  ];

  for (const name of candidates) {
    const full = path.join(projectDir, name);
    if (fs.existsSync(full) && fs.statSync(full).isFile()) {
      return full;
    }
  }
  return null;
}

async function main() {
  try {
    const projectDir = process.argv[2] && String(process.argv[2]).trim() ? process.argv[2] : process.cwd();

    const plopfilePath = findPlopfile(projectDir);
    if (!plopfilePath) {
      // Warn to stderr, but keep a valid JSON array on stdout
      console.warn(`[plop] No plopfile found in directory: ${projectDir}`);
      console.log('[]');
      return;
    }

    const projectRequire = createRequire(path.join(projectDir, 'package.json'));

    let nodePlop;
    try {
      const nodePlopModule = projectRequire('node-plop');
      nodePlop = nodePlopModule && (nodePlopModule.default || nodePlopModule);
      if (typeof nodePlop !== 'function') {
        throw new Error('node-plop module did not export a function');
      }
    } catch (e) {
      console.error(`[plop] Failed to load node-plop module: ${e && e.message ? e.message : String(e)}`);
      console.log('[]');
      return;
    }

    // nodePlop can be sync or async depending on version; using await covers both
    const plop = await nodePlop(plopfilePath, { destBasePath: projectDir });
    const list = typeof plop.getGeneratorList === 'function' ? plop.getGeneratorList() : [];
    const result = Array.isArray(list)
      ? list.map((g) => ({ name: g.name || '', description: g.description || '' }))
      : [];

    console.log(JSON.stringify(result, null, 2));
  } catch (err) {
    // Print the error to stderr, but keep stdout as valid JSON so the caller can parse it.
    try {
      const message = (err && err.stack) ? err.stack : (err && err.message) ? err.message : String(err);
      console.error(`[plop] Unexpected error while listing generators: ${message}`);
    } catch (_) {
      // ignore formatting issue
      console.error('[plop] Unexpected error while listing generators');
    }
    // Soft fail – output empty list to stdout
    console.log('[]');
  }
}

void main();
