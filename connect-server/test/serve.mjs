// Starts the local server for the Kotlin end-to-end tests and stays up until killed.
import { startServer } from './harness.mjs';
await startServer(Number(process.argv[2] ?? 8795));
console.log('ready');
setInterval(() => {}, 1 << 30);
