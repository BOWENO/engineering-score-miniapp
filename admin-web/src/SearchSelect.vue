<script setup lang="ts">
import {computed,ref} from 'vue'
import type {Option} from './workflow'
const props=defineProps<{modelValue:string|string[];options:Option[];multiple?:boolean;label:string;disabled?:boolean}>()
const emit=defineEmits(['update:modelValue'])
const query=ref('')
const selected=computed(()=>Array.isArray(props.modelValue)?props.modelValue:[props.modelValue])
const filtered=computed(()=>props.options.filter(o=>o.label.toLowerCase().includes(query.value.trim().toLowerCase())))
function toggle(value:string){emit('update:modelValue',props.multiple?(selected.value.includes(value)?selected.value.filter(x=>x!==value):[...selected.value,value]):value)}
</script>
<template><div class="search-select"><input v-model="query" type="search" :aria-label="'搜索'+label" :placeholder="'搜索'+label" :disabled="disabled"><p>已选 {{selected.filter(Boolean).length}} 项 <span v-for="o in options.filter(o=>selected.includes(o.value))" :key="o.value" class="selection-chip">{{o.label}} <button type="button" :disabled="disabled" :aria-label="'移除'+o.label" @click="emit('update:modelValue',multiple?selected.filter(x=>x!==o.value):'')">×</button></span></p><div class="selection-options"><label v-for="o in filtered" :key="o.value"><input :type="multiple?'checkbox':'radio'" :checked="selected.includes(o.value)" :disabled="disabled" @change="toggle(o.value)">{{o.label}}</label><p v-if="!filtered.length">没有匹配项，请调整搜索条件</p></div></div></template>
<style scoped>.search-select{min-width:0}.search-select>input{width:100%}.selection-options{max-height:180px;overflow:auto;border:1px solid #ccdada;border-radius:8px;padding:8px}.selection-options label{display:flex;align-items:center;gap:8px;padding:8px;margin:0;cursor:pointer}.selection-options input{width:auto!important;margin:0!important}.selection-chip{display:inline-block;padding:3px 6px;background:#e4f2ef;margin:3px;border-radius:6px}.selection-chip button{padding:0 5px;color:#245d55;background:transparent}.search-select p{font-size:13px;line-height:1.8}</style>
