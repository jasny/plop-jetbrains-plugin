// Helper script to describe a specific Plop generator's prompts.
//
// Usage: node describe-generator.js <projectDir> <generatorName>
// - Loads the project's plopfile using node-plop resolved from the project directory
// - Prints a JSON object to stdout: { name, description, prompts: [{ type, name, message, default, choices }] }
// - On any (soft) error, prints an object with empty prompts and exits 0; warnings go to stderr

/* eslint-disable no-console */

const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');

function findPlopfile(projectDir) {
  const candidates = [
    'plopfile.js',
    'plopfile.cjs',
  ];
  for (const name of candidates) {
    const full = path.join(projectDir, name);
    if (fs.existsSync(full) && fs.statSync(full).isFile()) {
      return full;
    }
  }
  return null;
}

function normalizeChoice(choice) {
  // Inquirer choices can be strings or objects { name, value }
  if (choice == null) return null;
  if (typeof choice === 'string') return { name: choice, value: choice };
  if (typeof choice === 'object') {
    const name = choice.name != null ? choice.name : String(choice.value ?? '');
    const value = choice.value != null ? choice.value : choice.name;
    return { name, value };
  }
  return { name: String(choice), value: choice };
}

function mapQuestion(q) {
  if (!q || typeof q !== 'object') return null;
  const out = {
    type: q.type,
    name: q.name,
    message: q.message,
    default: q.default,
  };
  if (Array.isArray(q.choices)) {
    // Preserve raw choices but normalize to array of simple values when possible
    out.choices = q.choices.map((c) => c);
  }
  return out;
}

async function main() {
  try {
    const projectDir = process.argv[2] && String(process.argv[2]).trim() ? process.argv[2] : process.cwd();
    const generatorName = process.argv[3] && String(process.argv[3]).trim() ? process.argv[3] : '';

    const fallback = JSON.stringify({ name: generatorName, description: '', prompts: [] }, null, 2);

    if (!generatorName) {
      console.warn('[plop] No generator name provided');
      console.log(fallback);
      return;
    }

    const plopfilePath = findPlopfile(projectDir);
    if (!plopfilePath) {
      console.warn(`[plop] No plopfile found in directory: ${projectDir}`);
      console.log(fallback);
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
      console.log(fallback);
      return;
    }

    const plop = await nodePlop(plopfilePath, { destBasePath: projectDir });
    const gen = typeof plop.getGenerator === 'function' ? plop.getGenerator(generatorName) : null;
    if (!gen) {
      console.warn(`[plop] Generator not found: ${generatorName}`);
      console.log(fallback);
      return;
    }

    let questions = [];
    try {
      const p = gen.prompts;
      if (Array.isArray(p)) {
        questions = p;
      } else if (typeof p === 'function') {
        // Try to call prompt factory in a non-interactive way. Many plopfiles return an array from this function.
        // Provide a minimal fake inquirer object if needed by user code.
        const fakeInquirer = {};
        const res = p.length >= 1 ? p(fakeInquirer) : p();
        const awaited = (res && typeof res.then === 'function') ? await res : res;
        if (Array.isArray(awaited)) {
          questions = awaited;
        } else {
          console.warn('[plop] Generator prompts function did not return an array; falling back to empty prompts');
        }
      }
    } catch (e) {
      console.error(`[plop] Failed to resolve prompts for generator '${generatorName}': ${e && e.message ? e.message : String(e)}`);
      // keep questions as empty
    }

    const mapped = Array.isArray(questions)
      ? questions.map(mapQuestion).filter(Boolean)
      : [];

    const result = {
      name: gen.name || generatorName,
      description: gen.description || '',
      prompts: mapped,
    };

    console.log(JSON.stringify(result, null, 2));
  } catch (err) {
    try {
      const message = (err && err.stack) ? err.stack : (err && err.message) ? err.message : String(err);
      console.error(`[plop] Unexpected error while describing generator: ${message}`);
    } catch (_) {
      console.error('[plop] Unexpected error while describing generator');
    }
    console.log(JSON.stringify({ name: '', description: '', prompts: [] }, null, 2));
  }
}

void main();
