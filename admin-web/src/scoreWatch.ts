export interface Change { revision:string }
export interface PendingChange { promise:Promise<Change>; cancel:()=>void }

// One outstanding long poll. A revision is acknowledged only after data loads successfully.
export function startScoreWatch(poll:(since:string)=>PendingChange,refresh:()=>Promise<void>,status:(connected:boolean)=>void=()=>{}):()=>void {
  let stopped=false,revision='',pending:PendingChange|undefined,timer:ReturnType<typeof setTimeout>|undefined
  let retry=1000,lastRefresh=0
  async function next(){
    if(stopped)return
    try{
      pending=poll(revision)
      const change=await pending.promise
      if(stopped)return
      // Periodic reconciliation also covers role changes and server-side maintenance.
      if(change.revision!==revision||Date.now()-lastRefresh>=60000){
        await refresh()
        if(stopped)return
        revision=change.revision;lastRefresh=Date.now()
      }
      status(true);retry=1000
    }catch{
      if(stopped)return
      status(false);timer=setTimeout(next,retry);retry=Math.min(retry*2,15000);return
    }
    if(!stopped)timer=setTimeout(next,100)
  }
  void next()
  return ()=>{stopped=true;if(timer)clearTimeout(timer);pending?.cancel()}
}
