// 仅测试使用：以显式测试code驱动原生Page，经真实TCP HTTP调用测试classpath服务器。
// 不伪称wx.login、微信网络栈或真机通过；产品网络层不会导入本文件。
const fs = require('node:fs');
function createHttpRuntime({ origin = 'http://127.0.0.1:18081', code, confirm = true } = {}) {
  if (!code) throw new Error('测试适配器必须显式指定测试code');
  const saved = new Map(), calls = [], routes = [], feedback = [];
  const adaptUrl = url => origin + url.replace(/^https?:\/\/[^/]+/, '');
  const wx = {
    getStorageSync: key => saved.get(key), setStorageSync: (key, value) => saved.set(key, value), removeStorageSync: key => saved.delete(key),
    login: options => options.success({ code }),
    request(options) {
      const method = options.method || 'GET'; let url = adaptUrl(options.url);
      if (method === 'GET' && options.data) { const query = new URLSearchParams(options.data); url += (url.includes('?') ? '&' : '?') + query; }
      fetch(url, { method, headers: options.header, body: method === 'GET' || options.data === undefined ? undefined : JSON.stringify(options.data) })
        .then(async response => { const data = await response.json(); calls.push({ method, path: new URL(url).pathname, status: response.status, code: data.code }); options.success({ statusCode: response.status, data }); })
        .catch(error => { calls.push({ method, path: new URL(url).pathname, error: error.message }); options.fail({ errMsg: error.message }); });
    },
    uploadFile(options) {
      const form = new FormData(); Object.entries(options.formData || {}).forEach(([key,value]) => form.append(key,value));
      form.append(options.name, new Blob([fs.readFileSync(options.filePath)], { type: 'image/png' }), 'test.png');
      fetch(adaptUrl(options.url), { method: 'POST', headers: options.header, body: form }).then(async response => {
        const data = await response.text(); calls.push({ method: 'POST', path: '/api/v1/images', status: response.status, code: JSON.parse(data).code }); options.success({ statusCode: response.status, data });
      }).catch(error => options.fail({ errMsg: error.message }));
    },
    showToast: options => feedback.push(options.title),
    showModal: options => { feedback.push(options.title); options.success?.({ confirm, cancel: !confirm }); },
    navigateTo: options => routes.push(options.url), redirectTo: options => routes.push(options.url), switchTab: options => routes.push(options.url), navigateBack: () => routes.push('back'),
    hideKeyboard() {}, stopPullDownRefresh() {}, pageScrollTo() {},
  };
  return { wx, saved, calls, routes, feedback };
}
module.exports = { createHttpRuntime };
