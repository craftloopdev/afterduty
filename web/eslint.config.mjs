import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  // Override default ignores of eslint-config-next.
  globalIgnores([
    // Default ignores of eslint-config-next:
    ".next/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
    // Native shells and local screenshot artifacts hold synced, minified bundles,
    // not source — without these, a worktree that has built the apps lints them.
    "android/**",
    "ios/**",
    "screenshots/**",
  ]),
  {
    // P0-9: condition links must be built with condHref() from @/lib/platform.
    // The native static export ships only the `/conditions/detail?id=` twin
    // (scripts/native-export.mjs stashes the dynamic `[id]` route), so a literal
    // `/conditions/${id}` href 404s in the iOS bundle. platform.ts is the one
    // place allowed to spell the web-canonical template.
    files: ["src/**/*.{ts,tsx}"],
    ignores: ["src/lib/platform.ts"],
    rules: {
      "no-restricted-syntax": [
        "error",
        {
          selector: 'TemplateLiteral[quasis.0.value.raw="/conditions/"]',
          message:
            "Build condition links with condHref(id) from @/lib/platform — a literal `/conditions/${id}` href 404s in the native export.",
        },
        {
          selector: 'BinaryExpression[operator="+"] > Literal[value="/conditions/"]',
          message:
            "Build condition links with condHref(id) from @/lib/platform — a literal '/conditions/' + id href 404s in the native export.",
        },
      ],
    },
  },
]);

export default eslintConfig;
