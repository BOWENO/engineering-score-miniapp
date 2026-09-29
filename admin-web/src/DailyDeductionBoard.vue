<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { apiGet } from './api/client'
interface Item { id:string;displayName:string;teamName:string;roleName:string;description:string;score:number;category:string;effectiveTime:string;occurredTime:string;underAppeal:boolean }
interface Board { date:string;today:string;scopeName:string;total:number;people:number;totalScore:number;page:number;hasMore:boolean;items:Item[] }
const board=ref<Board|null>(null),date=ref(''),busy=ref(false),error=ref('')
let sequence=0
async function load(more=false){
  if(more&&(busy.value||!board.value?.hasMore))return
  const current=++sequence,page=more?(board.value?.page||0)+1:0
  const queryDate=more?board.value?.date:date.value
  busy.value=true;error.value=''
  try{
    const result=await apiGet<Board>(`/publicity/daily-deductions?size=6&page=${page}${queryDate?'&date='+queryDate:''}`)
    if(current!==sequence)return
    board.value={...result,items:more?[...(board.value?.items||[]),...result.items]:result.items}
  }catch(e){if(current===sequence)error.value=e instanceof Error?e.message:'公示加载失败'}
  finally{if(current===sequence)busy.value=false}
}
onMounted(()=>load())
function changeDate(event:Event){date.value=(event.target as HTMLInputElement).value;board.value=null;load()}
defineExpose({refresh:async()=>{await load();if(error.value)throw new Error(error.value)}})
</script>

<template>
  <section class="daily-board" aria-label="每日扣分公示">
    <div class="board-top"><div><div class="board-eyebrow"><i></i>每日公示</div><h2>{{ !date || date===board?.today ? '今日扣分公示' : '每日扣分公示' }}</h2><p>技术员 · 助理工程师<span v-if="board?.scopeName"> / {{ board.scopeName }}</span></p></div><label class="board-date">公示日期<input type="date" :value="date || board?.date" :max="board?.today" @change="changeDate"></label></div>
    <div v-if="error" class="board-error" role="alert">公示暂时无法更新：{{ error }} <button @click="load()">重试</button></div>
    <div v-else-if="board">
      <div class="board-stats"><span><b>{{ board.total }}</b> 项扣分</span><span><b>{{ board.people }}</b> 位人员</span><span class="deduction"><b>{{ board.totalScore>0?'−':'' }}{{ board.totalScore }}</b> 实际扣分</span></div>
      <div v-if="!board.total" class="board-empty"><b>—</b><div>当日暂无已生效扣分<small>审核通过并计分后，将在这里公示</small></div></div>
      <div v-else class="board-items"><details v-for="item in board.items" :key="item.id" class="board-item"><summary><div class="item-top"><div><strong>{{ item.displayName }}</strong><span class="role">{{ item.roleName }}</span></div><b class="item-score">−{{ item.score }}<small> 分</small></b></div><p>{{ item.description }}</p><div class="item-foot"><span>{{ item.category }} · {{ item.effectiveTime }} 生效</span><span>{{ item.underAppeal?'申诉处理中':'展开事项 ⌄' }}</span></div></summary><div class="item-detail"><p>{{ item.description }}</p><span>{{ item.teamName }} · 事项发生 {{ item.occurredTime }}</span></div></details></div>
      <button v-if="board.hasMore" class="board-more" :disabled="busy" @click="load(true)">{{ busy?'加载中…':`展开更多（${board.items.length} / ${board.total}）` }}</button>
    </div>
    <div v-else class="board-empty">正在加载当日公示…</div>
    <footer><span>按生效日公示 · 已撤销事项不展示</span><button :disabled="busy" @click="load()">{{ busy?'更新中…':'刷新公示' }}</button></footer>
  </section>
</template>

<style scoped>
.daily-board{padding:26px 30px 12px;margin-bottom:26px;border:1px solid #eeddd1;border-radius:24px;background:linear-gradient(120deg,#fff4e9,#fff 55%);box-shadow:0 10px 30px #8346250a;color:#493b32}.board-top{display:flex;align-items:center;justify-content:space-between;gap:20px}.board-eyebrow{display:flex;align-items:center;gap:8px;color:#985b37;font-size:11px;font-weight:650;letter-spacing:3px}.board-eyebrow i{height:7px;width:7px;background:#be683f;border-radius:50%}h2{margin:9px 0 8px;font-size:28px;letter-spacing:1px}.board-top p{margin:0;color:#948273;font-size:12px}.board-date{display:flex;align-items:center;gap:10px;color:#8b725c;font-size:12px}.board-date input{padding:10px;border:1px solid #e8d7c5;border-radius:10px;background:#fff;color:#75563c}.board-stats{display:flex;gap:40px;align-items:baseline;margin:24px 0 4px;padding:20px 0;border-top:1px solid #f0e4d8;border-bottom:1px solid #f0e4d8;color:#94816f;font-size:12px}.board-stats b{color:#4a3b30;font-size:29px;margin-right:7px;font-variant-numeric:tabular-nums}.board-stats .deduction{margin-left:auto}.board-stats .deduction b{color:#b04c38}.board-items{display:grid;grid-template-columns:1fr 1fr;column-gap:32px}.board-item{padding:19px 0;border-bottom:1px solid #f2e9df;min-width:0}.board-item summary{cursor:pointer;list-style:none}.board-item summary::-webkit-details-marker{display:none}.item-top,.item-foot{display:flex;align-items:center;justify-content:space-between;gap:10px}.item-top strong{font-size:16px}.role{margin-left:10px;padding:3px 6px;border-radius:5px;background:#f3f1ed;color:#8c8376;font-size:10px;white-space:nowrap}.item-score{color:#b04c38;font-size:22px;white-space:nowrap}.item-score small{font-size:11px;font-weight:400}.board-item summary p{margin:12px 0;color:#706357;line-height:1.7;font-size:13px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;overflow-wrap:anywhere}.item-foot{font-size:10px;color:#a29486}.item-detail{border-left:2px solid #e5c8b3;padding:0 12px;margin-top:16px;color:#776655;font-size:12px;line-height:1.8;overflow-wrap:anywhere}.item-detail span{font-size:11px;color:#998775}.board-empty{display:flex;align-items:center;justify-content:center;gap:16px;padding:32px 0;color:#7f7162;font-size:14px}.board-empty>b{background:#f5eee5;border-radius:14px;padding:15px;color:#b69d82}.board-empty small{display:block;font-size:11px;margin-top:7px;color:#aa9b8a}footer{display:flex;justify-content:space-between;align-items:center;gap:12px;padding-top:12px;font-size:11px;color:#ad9e8b}button{border:0;cursor:pointer;color:#936342;background:transparent;border-radius:8px;padding:9px 12px;font-size:12px}button:disabled{opacity:.6;cursor:wait}.board-more{display:block;width:100%;background:#faf4ed;margin-top:16px;padding:13px}.board-error{padding:24px 0;color:#aa4d3b;font-size:13px}.board-item summary:focus-visible,button:focus-visible,input:focus-visible{outline:2px solid #a87043;outline-offset:4px}@media(max-width:800px){.daily-board{padding:22px 18px 10px}.board-top{align-items:flex-start}.board-date{flex-direction:column;gap:4px;align-items:flex-end}.board-items{grid-template-columns:1fr}.board-stats{gap:20px}h2{font-size:23px}.board-stats b{font-size:24px}}
</style>
