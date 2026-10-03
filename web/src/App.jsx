import AppRouter from './router'

// App 现在极薄：只负责挂载路由。真正的页面在 router 里配置
export default function App() {
  return <AppRouter />
}
