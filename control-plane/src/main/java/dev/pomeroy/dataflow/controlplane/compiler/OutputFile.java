package dev.pomeroy.dataflow.controlplane.compiler;

/**
 * One resolved file-production step: the Catalog File Definition the plan will
 * produce. {@code namePattern} keeps its tokens — {@code {businessDate}} and the
 * {@code splitBy} field's values (one file per value) resolve at run time.
 */
public record OutputFile(String fileDefinitionId, String namePattern, String splitBy) {
}
