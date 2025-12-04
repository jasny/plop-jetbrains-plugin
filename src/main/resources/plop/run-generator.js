// Helper script to run a Plop generator non-interactively with provided answers.
//
// Usage: node run-generator.js <projectDir> <plopfilePath> <moduleKind> <generatorName> <answersJson>
// - Resolves node-plop from the target project
// - Loads the project's plopfile (CJS/ESM handled similarly to other helpers)
// - Runs the generator with the given answers without prompting
// - Prints a single JSON object to stdout: { ok, message, changes? }
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

function safePrintJson(obj) {
  try {
    console.log(JSON.stringify(obj, null, 2));
  } catch (e) {
    // As a last resort
    console.log('{"ok":false,"message":"Failed to stringify result"}');
  }
}

async function main() {
  try {
    const projectDir = process.argv[2] && String(process.argv[2]).trim() ? process.argv[2] : process.cwd();
    const plopfilePath = process.argv[3] && String(process.argv[3]).trim() ? process.argv[3] : '';
    const moduleKind = process.argv[4] && String(process.argv[4]).trim() ? process.argv[4] : 'cjs';
    const generatorName = process.argv[5] && String(process.argv[5]).trim() ? process.argv[5] : '';
    const answersJson = process.argv[6] && String(process.argv[6]).trim() ? process.argv[6] : '{}';

    if (!generatorName) {
      console.warn('[plop] No generator name provided');
      safePrintJson({ ok: false, message: 'No generator name provided' });
      return;
    }
    if (!plopfilePath) {
      console.warn('[plop] Missing plopfile path argument');
      safePrintJson({ ok: false, message: 'Plopfile not found' });
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
      safePrintJson({ ok: false, message: 'node-plop module not found in project' });
      return;
    }

    const entryPath = moduleKind === 'esm' ? createEsmBridge(plopfilePath) : plopfilePath;
    const plop = await nodePlop(entryPath, { destBasePath: projectDir });
    const gen = typeof plop.getGenerator === 'function' ? plop.getGenerator(generatorName) : null;
    if (!gen) {
      console.warn(`[plop] Generator not found: ${generatorName}`);
      safePrintJson({ ok: false, message: `Generator not found: ${generatorName}` });
      return;
    }

    let answers = {};
    try {
      answers = JSON.parse(answersJson);
      if (answers == null || typeof answers !== 'object') answers = {};
    } catch (e) {
      console.error(`[plop] Failed to parse answers JSON: ${e && e.message ? e.message : String(e)}`);
      safePrintJson({ ok: false, message: 'Invalid answers JSON' });
      return;
    }

    // Run actions non-interactively with provided answers
    let result;
    try {
      result = await gen.runActions(answers, { runHooks: true });
    } catch (e) {
      console.error(`[plop] Error while running generator '${generatorName}': ${e && e.stack ? e.stack : e && e.message ? e.message : String(e)}`);
      safePrintJson({ ok: false, message: `Failed to run generator: ${e && e.message ? e.message : 'Unknown error'}` });
      return;
    }

    const changes = Array.isArray(result && result.changes)
      ? result.changes.map((c) => (c && (c.path || c.file || c.dest || c.absPath)) || '').filter(Boolean)
      : [];
    const failures = Array.isArray(result && result.failures) ? result.failures : [];
    const ok = failures.length === 0;
    const message = ok
      ? (changes.length > 0 ? `Plop: ${changes.length} file(s) generated` : 'Plop: generator completed')
      : `Plop: ${failures.length} failure(s) while generating`;

    safePrintJson({ ok, message, changes });
  } catch (err) {
    try {
      const message = (err && err.stack) ? err.stack : (err && err.message) ? err.message : String(err);
      console.error(`[plop] Unexpected error while running generator: ${message}`);
    } catch (_) {
      console.error('[plop] Unexpected error while running generator');
    }
    safePrintJson({ ok: false, message: 'Unexpected error while running generator' });
  }
}

void main();
