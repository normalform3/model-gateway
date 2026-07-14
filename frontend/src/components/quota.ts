export type QuotaScope = "TEAM" | "MEMBER";
export type QuotaUnit = "MILLION" | "HUNDRED_MILLION" | "TRILLION";

export const QUOTA_UNITS: Record<QuotaUnit, { label: string; multiplier: bigint; decimals: number }> = {
  MILLION: { label: "百万", multiplier: 1_000_000n, decimals: 6 },
  HUNDRED_MILLION: { label: "亿", multiplier: 100_000_000n, decimals: 8 },
  TRILLION: { label: "万亿", multiplier: 1_000_000_000_000n, decimals: 12 }
};

export const QUOTA_MAX: Record<QuotaScope, number> = {
  MEMBER: 99_900_000_000,
  TEAM: 999_000_000_000_000
};

export function availableUnits(scope: QuotaScope): QuotaUnit[] {
  return scope === "TEAM" ? ["MILLION", "HUNDRED_MILLION", "TRILLION"] : ["MILLION", "HUNDRED_MILLION"];
}

export function defaultQuota(scope: QuotaScope, mode: string): number {
  if (scope === "MEMBER") return mode === "WEEKLY" ? 500_000_000 : 100_000_000;
  return mode === "WEEKLY" ? 10_000_000_000 : 1_000_000_000;
}

export function parseQuotaAmount(quantity: string, unit: QuotaUnit, scope: QuotaScope): number | null {
  const value = quantity.trim();
  const match = /^(\d+)(?:\.(\d+))?$/.exec(value);
  if (!match) return null;
  const definition = QUOTA_UNITS[unit];
  const fraction = match[2] ?? "";
  if (fraction.length > definition.decimals) return null;
  const scale = 10n ** BigInt(fraction.length);
  const raw = BigInt(match[1]) * definition.multiplier + (fraction ? BigInt(fraction) * definition.multiplier / scale : 0n);
  if ((fraction && (BigInt(fraction) * definition.multiplier) % scale !== 0n) || raw <= 0n || raw > BigInt(QUOTA_MAX[scope])) return null;
  return Number(raw);
}

export function toQuotaInput(tokens: number, scope: QuotaScope): { quantity: string; unit: QuotaUnit } {
  const raw = BigInt(tokens);
  const unit = [...availableUnits(scope)].reverse().find(candidate => raw >= QUOTA_UNITS[candidate].multiplier) ?? "MILLION";
  return { quantity: amountInUnit(raw, QUOTA_UNITS[unit]), unit };
}

export function formatTokenCount(tokens: number | null | undefined): string {
  if (tokens === null || tokens === undefined) return "不限";
  const scope: QuotaScope = tokens > QUOTA_MAX.MEMBER ? "TEAM" : "MEMBER";
  const { quantity, unit } = toQuotaInput(tokens, scope);
  return `${quantity} ${QUOTA_UNITS[unit].label}`;
}

export function amountInUnit(tokens: bigint, unit: { multiplier: bigint; decimals: number }): string {
  const whole = tokens / unit.multiplier;
  const remainder = tokens % unit.multiplier;
  if (remainder === 0n) return whole.toString();
  const digits = remainder.toString().padStart(unit.decimals, "0").replace(/0+$/, "");
  return `${whole}.${digits}`;
}
