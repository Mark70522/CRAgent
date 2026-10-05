import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { ConfigProvider, App as AntApp } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import App from './App.jsx'
import { theme } from './theme'
import './styles.css'

// Served by cr-agent under /app/ (see vite.config.js base); the router has to know that prefix.
ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <BrowserRouter basename="/app">
      <ConfigProvider locale={zhCN} theme={theme}>
        <AntApp>
          <App />
        </AntApp>
      </ConfigProvider>
    </BrowserRouter>
  </React.StrictMode>,
)
