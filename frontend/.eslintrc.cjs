// ESLint 配置（配合 eslint-plugin-vue 与 prettier）
module.exports = {
  root: true,
  env: { browser: true, es2021: true, node: true },
  extends: [
    'plugin:vue/vue3-recommended', // Vue3 官方推荐规则
    'eslint:recommended',
    '@vue/eslint-config-prettier' // 关闭与 prettier 冲突的规则
  ],
  parserOptions: { ecmaVersion: 'latest', sourceType: 'module' },
  rules: {
    'vue/multi-word-component-names': 'off', // 允许单单词组件名（如 Login.vue）
    'no-unused-vars': ['warn', { argsIgnorePattern: '^_' }],
    'vue/no-v-html': 'off'
  }
}
