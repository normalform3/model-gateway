<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { QUOTA_MAX, QUOTA_UNITS, availableUnits, parseQuotaAmount, toQuotaInput, type QuotaScope, type QuotaUnit } from "./quota";

const props = withDefaults(defineProps<{ modelValue: number | null; scope: QuotaScope; disabled?: boolean }>(), { disabled: false });
const emit = defineEmits<{ "update:modelValue": [value: number | null]; validity: [valid: boolean] }>();
const quantity = ref("");
const unit = ref<QuotaUnit>("MILLION");
const error = ref("");
const presets = computed(() => props.scope === "TEAM"
  ? [{ label: "10 亿", tokens: 1_000_000_000 }, { label: "100 亿", tokens: 10_000_000_000 }, { label: "1 万亿", tokens: 1_000_000_000_000 }, { label: "10 万亿", tokens: 10_000_000_000_000 }]
  : [{ label: "1 亿", tokens: 100_000_000 }, { label: "5 亿", tokens: 500_000_000 }, { label: "10 亿", tokens: 1_000_000_000 }, { label: "50 亿", tokens: 5_000_000_000 }]);

function sync(tokens: number | null) {
  if (tokens === null || tokens === undefined) { quantity.value = ""; unit.value = availableUnits(props.scope)[0]; return; }
  const value = toQuotaInput(tokens, props.scope);
  quantity.value = value.quantity; unit.value = value.unit; error.value = "";
}
function commit() {
  if (props.disabled) { emit("update:modelValue", null); emit("validity", true); return; }
  const value = parseQuotaAmount(quantity.value, unit.value, props.scope);
  if (value === null) {
    error.value = `请输入可精确换算的正数，单次上限 ${toQuotaInput(QUOTA_MAX[props.scope], props.scope).quantity} ${QUOTA_UNITS[toQuotaInput(QUOTA_MAX[props.scope], props.scope).unit].label}`;
    emit("validity", false);
    return;
  }
  error.value = ""; emit("update:modelValue", value); emit("validity", true);
}
function changeUnit(next: QuotaUnit) {
  if (props.modelValue !== null) quantity.value = toQuotaInput(props.modelValue, props.scope).unit === next
    ? toQuotaInput(props.modelValue, props.scope).quantity
    : amountForSelectedUnit(props.modelValue, next);
  unit.value = next; commit();
}
function amountForSelectedUnit(tokens: number, selected: QuotaUnit): string {
  const definition = QUOTA_UNITS[selected];
  const raw = BigInt(tokens); const whole = raw / definition.multiplier; const remainder = raw % definition.multiplier;
  return remainder === 0n ? whole.toString() : `${whole}.${remainder.toString().padStart(definition.decimals, "0").replace(/0+$/, "")}`;
}
function choosePreset(tokens: number) { sync(tokens); commit(); }

watch(() => [props.modelValue, props.scope] as const, ([tokens]) => sync(tokens), { immediate: true });
watch(() => props.disabled, disabled => { if (disabled) { error.value = ""; emit("validity", true); } });
</script>

<template>
  <div class="quota-amount-input" :class="{ disabled }">
    <div class="quota-fields">
      <el-input v-model="quantity" inputmode="decimal" :disabled="disabled" placeholder="例如 5" @input="commit" @blur="commit" />
      <el-select :model-value="unit" :disabled="disabled" @update:model-value="changeUnit">
        <el-option v-for="item in availableUnits(scope)" :key="item" :label="QUOTA_UNITS[item].label" :value="item" />
      </el-select>
    </div>
    <div v-if="!disabled" class="quota-presets"><span>常用</span><button v-for="preset in presets" :key="preset.label" type="button" @click="choosePreset(preset.tokens)">{{ preset.label }}</button></div>
    <small v-if="disabled" class="quota-hint">不限额度仅记录用量，不参与有限额度占比。</small>
    <small v-else-if="error" class="quota-error">{{ error }}</small>
  </div>
</template>
