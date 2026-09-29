// Browser-to-real-backend bridge. The URL is created only by the isolated test harness.
const http = require('node:http'), fs = require('node:fs'), path = require('node:path');
const backend = new URL(fs.readFileSync(path.join(__dirname,'../server/target/acceptance-api-url.txt'),'utf8'));
if(backend.hostname !== '127.0.0.1' || backend.protocol !== 'http:') throw new Error('Only loopback test backend allowed');
const root = path.resolve(__dirname,'../admin-web/dist');
http.createServer((req,res) => {
  const url = new URL(req.url,'http://127.0.0.1');
  if(url.pathname.startsWith('/api/')) {
    const upstream = http.request({hostname:'127.0.0.1',port:backend.port,path:req.url,method:req.method,headers:req.headers},reply=>{
      res.writeHead(reply.statusCode,reply.headers); reply.pipe(res);
    });
    upstream.on('error',()=>{res.writeHead(502);res.end('Local acceptance backend unavailable');});
    req.pipe(upstream); return;
  }
  const file=path.resolve(root,'.'+decodeURIComponent(url.pathname==='/'?'/index.html':url.pathname));
  if(!file.startsWith(root+path.sep)||!fs.existsSync(file)){res.writeHead(404);res.end();return;}
  res.setHeader('Content-Type',file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':'text/html');
  fs.createReadStream(file).pipe(res);
}).listen(4181,'127.0.0.1',()=>console.log('Real local browser acceptance: http://127.0.0.1:4181'));
