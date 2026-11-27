import { defineConfig } from 'vite';

export default defineConfig({
  appType: 'mpa',
  build: {
    rollupOptions: {
      input: ['index.html', 'walkthrough/index.html', 'architecture/index.html',
        'lab/index.html', 'lab/debugger/index.html', 'evidence/index.html', '404.html'],
    },
  },
});
