import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import 'element-plus/dist/index.css'
import './styles/tokens.css'
import './styles/base.css'
import './styles/admin.css'
import './styles/content-management.css'
import App from './App.vue'
import { createAdminRouter } from './router'

const pinia = createPinia()
const router = createAdminRouter(pinia)

createApp(App).use(pinia).use(router).use(ElementPlus).mount('#app')
