Plop for JetBrains IDEs
=======================

### [Get the plugin on JetBrains Marketplace](https://plugins.jetbrains.com/plugin/29277-plop)

<img width="640" height="400" alt="Screenshot Plop JetBrains Plugin" src="https://github.com/user-attachments/assets/a88e3427-5822-4acb-ba82-7cdb53a1a672" />

Run [Plop code generators](https://plopjs.com) right from your JetBrains IDE. The plugin detects your project's plopfile, lists available generators, and adds a Plop submenu to the New context menu so you can scaffold files without leaving the IDE.

Features
- Auto-detects plopfile.js / plopfile.ts in your project (and per content root in monorepos)
- New | Plop submenu with dynamically listed generators
- Interactive prompts shown in the IDE; runs Plop via Node.js in the background
- Supports plopfile.ts through ts-node/register when available

Requirements
- Node.js installed and available on PATH
- A project-level Plop setup (plopfile and the plop package)
- Optional: JetBrains NodeJS plugin for improved Node interpreter detection

Installation
From JetBrains Marketplace (recommended)
1. Open your IDE Settings/Preferences → Plugins
2. Search for "Plop"
3. Click Install and restart the IDE

From source (for development)
1. Clone this repository
2. Run: ./gradlew buildPlugin
3. Install the built zip from build/distributions via Settings/Preferences → Plugins → Gear icon → Install Plugin from Disk…

Usage
1. Open a project containing a plopfile (plopfile.js or plopfile.ts)
2. Right‑click in the Project tool window where you want to generate files
3. Choose New → Plop → <your generator>
4. Follow the prompts; generated files and modifications will be applied to the project

Notes and limitations
- Generators are discovered per content root, which works well for monorepos.
- If no generators are found, ensure Node is on PATH and the plopfile is present and valid.
- For TypeScript plopfiles, a local ts-node dependency is recommended.

Development
- Minimum setup: JDK 17+, Gradle (wrapper included)
- Useful tasks:
  - Run IDE for debugging: ./gradlew runIde
  - Build plugin: ./gradlew buildPlugin

License
MIT © Jasny — see LICENSE
