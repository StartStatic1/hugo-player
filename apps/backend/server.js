const express = require('express');
const cors = require('cors');
const app = express();
app.use(cors());
app.use(express.json());

const devices = new Map();

app.post('/devices/register', (req, res) => {
  const { deviceId, deviceKey } = req.body;
  if (!deviceId || !deviceKey) return res.status(400).json({ error: 'missing credentials' });
  devices.set(deviceId, { deviceKey, status: 'active', createdAt: new Date().toISOString() });
  res.json({ success: true, deviceId });
});

app.get('/devices/status', (req, res) => {
  const device = devices.get(req.query.deviceId);
  res.json({ active: !!device, status: device?.status || 'not_found' });
});

app.listen(3000, () => console.log('Hugo Player API on port 3000'));