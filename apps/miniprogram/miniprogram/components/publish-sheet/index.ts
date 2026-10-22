Component({
  properties: {
    visible: { type: Boolean, value: false },
  },
  methods: {
    close() {
      this.triggerEvent('close')
    },
    choose(event: WechatMiniprogram.TouchEvent) {
      const type = event.currentTarget.dataset.type
      if (type === 'post' || type === 'product') {
        this.triggerEvent('select', { type })
      }
    },
    stopTap() {},
  },
})
