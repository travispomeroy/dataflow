/**
 * The TS half of the semantic rule set's keep-honest contract (issue #30): the mirror
 * must yield exactly the rule ids each shared violation example in
 * `e2e/canonical/violations/` declares — no more, no less, set semantics — and no
 * violations at all for the canonical Positions Feed. The Java authority
 * (`SemanticValidationExampleContractTests`) asserts the same files, so a rule changed
 * on one side breaks the other until both move together.
 */
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import type { DataflowConfig, SemanticRuleId } from '../index';
import { SEMANTIC_RULE_MESSAGES, validate } from '../index';

const CANONICAL_DIR = join(import.meta.dirname, '..', '..', '..', '..', '..', 'e2e', 'canonical');

const VIOLATIONS_DIR = join(CANONICAL_DIR, 'violations');

const ALL_RULES = Object.keys(SEMANTIC_RULE_MESSAGES).sort();

interface ViolationExample {
	description: string;
	rules: SemanticRuleId[];
	config: DataflowConfig;
}

const examples = readdirSync(VIOLATIONS_DIR)
	.filter((file) => file.endsWith('.violation.json'))
	.sort()
	.map((file) => ({
		file,
		example: JSON.parse(readFileSync(join(VIOLATIONS_DIR, file), 'utf8')) as ViolationExample,
	}));

describe('the semantic mirror against the shared violation examples', () => {
	it('yields no violations for the canonical Positions Feed', () => {
		const canonical = JSON.parse(
			readFileSync(join(CANONICAL_DIR, 'positions-feed.config.json'), 'utf8'),
		) as DataflowConfig;

		expect(validate(canonical)).toEqual([]);
	});

	it.each(examples)('yields exactly the declared rule ids for $file', ({ example }) => {
		const yielded = [...new Set(validate(example.config).map((v) => v.rule))].sort();

		expect(yielded).toEqual(example.rules);
	});

	it('covers every rule id across the examples, with multi-violation configs', () => {
		const declared = examples.flatMap(({ example }) => example.rules);

		expect([...new Set(declared)].sort()).toEqual(ALL_RULES);
		expect(examples.some(({ example }) => example.rules.length >= 3)).toBe(true);
	});

	it('phrases every violation with the user-facing message for its rule', () => {
		for (const { example } of examples) {
			for (const violation of validate(example.config)) {
				expect(violation.message).toBe(SEMANTIC_RULE_MESSAGES[violation.rule]);
			}
		}
	});
});
