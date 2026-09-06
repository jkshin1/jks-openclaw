import { createHealthHandler, HEALTH_METHOD } from './snapshot.mjs';

// Deliberately registers no model Tool, hook, service, command, HTTP route, or background work.
export default {
  id: 'personaledge-health',
  name: 'Personal Edge host health',
  version: '1.0.0',
  register(api) {
    api.registerGatewayMethod(HEALTH_METHOD, createHealthHandler(), {
      scope: 'operator.read',
      profileAccess: 'required',
    });
  },
};
