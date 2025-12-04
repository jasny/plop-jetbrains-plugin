// Simple helper script to list Plop generators for a given project directory.
//
// Usage: node list-generators.js <projectDir> <plopfilePath> <moduleKind>
// - Loads a project's plopfile (CJS/ESM) using node-plop resolved from that project.
// - Prints a JSON array to stdout: [{ name, description }, ...]
// - On any error, prints [] and exits with code 0 (soft fail).

/* eslint-disable no-console */

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { createRequire, pathToFileURL } = require('node:module');

function createEsmBridge(plopfileAbsPath) {
  const fileUrl = String(pathToFileURL(plopfileAbsPath));
  const content = `module.exports = async function(plop){
  const mod = await import(${JSON.stringify(fileUrl)});
  const fn = typeof mod === 'function' ? mod : (typeof mod.default === 'function' ? mod.default : null);
  if (!fn) throw new Error('Plopfile does not export a function');
  return fn(plop);
};`;
  const tmp = path.join(os.tmpdir(), `plop-bridge-${Date.now()}-${Math.random().toString(36).slice(2)}.cjs`);
  fs.writeFileSync(tmp, content, 'utf8');
  return tmp;
}

async function main() {
  try {
    const projectDir = process.argv[2] && String(process.argv[2]).trim() ? process.argv[2] : process.cwd();
    const plopfilePath = process.argv[3] && String(process.argv[3]).trim() ? process.argv[3] : '';
    const moduleKind = process.argv[4] && String(process.argv[4]).trim() ? process.argv[4] : 'cjs';

    if (!plopfilePath) {
      console.warn('[plop] Missing plopfile path argument');
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

    // For ESM plopfiles, create a small CJS bridge that imports the ESM module and exports a function
    const entryPath = moduleKind === 'esm' ? createEsmBridge(plopfilePath) : plopfilePath;

    // nodePlop can be sync or async depending on version; using await covers both
    const plop = await nodePlop(entryPath, { destBasePath: projectDir });
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
