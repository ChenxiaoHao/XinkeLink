import { createApp } from 'vue'
import {
  ElAlert, ElButton, ElCheckbox, ElConfigProvider, ElDatePicker, ElDialog, ElDrawer,
  ElDropdown, ElDropdownItem, ElDropdownMenu, ElEmpty, ElForm, ElFormItem, ElInput,
  ElLoading, ElOption, ElPagination, ElRadio, ElSelect, ElSwitch, ElTabPane,
  ElTable, ElTableColumn, ElTabs, ElTag, ElTooltip,
} from 'element-plus'
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

const app = createApp(App).use(pinia).use(router)
for (const component of [
  ElAlert, ElButton, ElCheckbox, ElConfigProvider, ElDatePicker, ElDialog, ElDrawer,
  ElDropdown, ElDropdownItem, ElDropdownMenu, ElEmpty, ElForm, ElFormItem, ElInput,
  ElLoading, ElOption, ElPagination, ElRadio, ElSelect, ElSwitch, ElTabPane,
  ElTable, ElTableColumn, ElTabs, ElTag, ElTooltip,
]) app.use(component)
app.mount('#app')
