function parseAllowedOrigins(value = '') {
  return new Set(String(value)
    .split(',')
    .map((origin) => origin.trim())
    .filter(Boolean));
}

function createCorsMiddleware({ allowedOrigins, allowPrivateNetwork = false }) {
  return (req, res, next) => {
    const origin = req.get('Origin');
    if (!origin) {
      next();
      return;
    }

    let originHost = null;
    try {
      originHost = new URL(origin).host;
    } catch {
      originHost = null;
    }
    if (originHost === req.get('Host')) {
      next();
      return;
    }

    if (!allowedOrigins.has(origin)) {
      res.status(403).json({
        success: false,
        message: 'This website origin is not allowed to access the service.',
        status: 403
      });
      return;
    }

    res.vary('Origin');
    res.set('Access-Control-Allow-Origin', origin);
    res.set('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
    res.set('Access-Control-Allow-Headers', 'Content-Type, Accept');

    if (allowPrivateNetwork && req.get('Access-Control-Request-Private-Network') === 'true') {
      res.vary('Access-Control-Request-Private-Network');
      res.set('Access-Control-Allow-Private-Network', 'true');
    }

    if (req.method === 'OPTIONS') {
      res.sendStatus(204);
      return;
    }

    next();
  };
}

module.exports = {
  parseAllowedOrigins,
  createCorsMiddleware
};
