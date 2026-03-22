let systems = [];
let ports = [];

async function loadSystems() {
  try {
    const res = await fetch('/api/systems');
    const data = await res.json();
    systems = data.systems;
    const select = document.getElementById('systemType');
    select.innerHTML = '<option value="">Select controller...</option>';
    systems.forEach(s => {
      const opt = document.createElement('option');
      opt.value = s.id;
      opt.textContent = s.name;
      select.appendChild(opt);
    });
  } catch (err) {
    showStatus('Error loading systems: ' + err.message, 'error');
  }
}

async function loadPorts() {
  try {
    const res = await fetch('/api/ports');
    const data = await res.json();
    ports = data.ports;
    const select = document.getElementById('portName');
    const previousSelection = select.value;
    select.innerHTML = '<option value="">Select port...</option>';
    ports.forEach(p => {
      const opt = document.createElement('option');
      opt.value = p;
      opt.textContent = p;
      select.appendChild(opt);
    });
    // Keep the user's selection if the port is still present
    if (previousSelection && ports.includes(previousSelection)) {
      select.value = previousSelection;
    }
  } catch (err) {
    showStatus('Error loading ports: ' + err.message, 'error');
  }
}

document.getElementById('systemType').addEventListener('change', (e) => {
  const system = systems.find(s => s.id === e.target.value);
  if (system && system.connectionTypes.includes('network')) {
    document.getElementById('portGroup').style.display = 'none';
    document.getElementById('serialOptionsGroup').style.display = 'none';
    document.getElementById('networkGroup').style.display = 'block';
  } else {
    document.getElementById('portGroup').style.display = 'block';
    document.getElementById('serialOptionsGroup').style.display = 'block';
    document.getElementById('networkGroup').style.display = 'none';
    // Set default baud rate based on system type
    const baudRateSelect = document.getElementById('baudRate');
    if (e.target.value === 'xnet-elite') {
      baudRateSelect.value = '19200';
    } else {
      baudRateSelect.value = '';
    }
  }
});

document.getElementById('configForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const params = new URLSearchParams();
  params.append('id', document.getElementById('connId').value);
  params.append('systemType', document.getElementById('systemType').value);
  if (document.getElementById('userName').value) {
    params.append('userName', document.getElementById('userName').value);
  }
  if (document.getElementById('systemPrefix').value) {
    params.append('systemPrefix', document.getElementById('systemPrefix').value);
  }
  const system = systems.find(s => s.id === document.getElementById('systemType').value);
  if (system && system.connectionTypes.includes('network')) {
    params.append('host', document.getElementById('host').value);
    params.append('port', document.getElementById('port').value);
  } else {
    params.append('portName', document.getElementById('portName').value);
    const baudRate = document.getElementById('baudRate').value;
    if (baudRate) {
      params.append('baudRate', baudRate);
    }
    const flowControl = document.getElementById('flowControl').value;
    if (flowControl) {
      params.append('flowControl', flowControl);
    }
  }
  try {
    const res = await fetch('/connections/create?' + params, { method: 'POST' });
    const data = await res.json();
    if (res.ok) {
      showStatus('Connection created successfully!', 'success');
      loadConnections();
      document.getElementById('configForm').reset();
    } else {
      showStatus('Error: ' + (data.error || 'Unknown error'), 'error');
    }
  } catch (err) {
    showStatus('Error: ' + err.message, 'error');
  }
});

async function loadConnections() {
  try {
    const res = await fetch('/connections');
    const data = await res.json();
    const list = document.getElementById('connectionsList');
    if (data.connections.length === 0) {
      list.innerHTML = '<p>No active connections</p>';
    } else {
      list.innerHTML = data.connections.map(c => {
        let html = `<div class="connection-item"><strong>${c.id}</strong> (${c.systemType}) - `;
        html += `${c.connected ? '✅ Connected' : '❌ Disconnected'}`;
        if (c.connected && c.commandStation) {
          html += `<br><small>Command Station: `;
          if (c.commandStation.manufacturer) html += `${c.commandStation.manufacturer} `;
          if (c.commandStation.model) html += `${c.commandStation.model} `;
          if (c.commandStation.version) html += `v${c.commandStation.version}`;
          if (c.commandStation.versionString) html += ` (${c.commandStation.versionString})`;
          if (c.commandStation.type && c.commandStation.type !== '-1') html += ` hardware type: ${c.commandStation.type}`;
          if (c.commandStation.softwareVersion && c.commandStation.softwareVersion !== '-1') html += ` software version: ${c.commandStation.softwareVersion}`;
          html += `</small>`;
        }
        if (c.connected && (c.powerStatus || connectionPowerStatus[c.id])) {
          const powerStatus = c.powerStatus || connectionPowerStatus[c.id] || 'UNKNOWN';
          const powerIcon = powerStatus === 'ON' ? '🟢' : powerStatus === 'OFF' ? '🔴' : '🟡';
          html += `<br><small class="power-status">Power: ${powerIcon} ${powerStatus}</small> `;
          // Add power control buttons
          const isOn = powerStatus === 'ON';
          html += `<button onclick="setPower('${c.id}', '${isOn ? 'OFF' : 'ON'}')" class="btn btn-${isOn ? 'danger' : 'primary'}" style="padding: 4px 10px; font-size: 11px; margin-left: 5px;">Power ${isOn ? 'OFF' : 'ON'}</button>`;
        }
        if (c.connected) {
          html += `<br><small style="color: #666;">Roles: `;
          const roles = c.roles || [];
          if (roles.length === 0) {
            html += `None`;
          } else {
            html += roles.join(', ');
          }
          html += `</small>`;
          html += `<br><label style="font-size: 12px; color: #cccccc;"><input type="checkbox" ${roles.includes('throttles') ? 'checked' : ''} onchange="setControllerRole('${c.id}', 'throttles', this.checked)"> Throttles</label> `;
          html += `<label style="font-size: 12px; color: #cccccc; margin-left: 10px;"><input type="checkbox" ${roles.includes('accessories') ? 'checked' : ''} onchange="setControllerRole('${c.id}', 'accessories', this.checked)"> Accessories</label>`;
          html += `<br><button onclick="requestVersion('${c.id}')" style="margin-top: 5px; padding: 5px 10px; background: #0e639c; color: white; border: none; border-radius: 3px; cursor: pointer; font-size: 12px;">Get Version</button>`;
        }
        html += `</div>`;
        return html;
      }).join('');
    }
    // Update controller role info
    const throttleControllerId = data.connections.find(c => c.roles && c.roles.includes('throttles'))?.id;
    const accessoryControllerId = data.connections.find(c => c.roles && c.roles.includes('accessories'))?.id;
    const throttleInfo = document.getElementById('throttleControllerInfo');
    const accessoryInfo = document.getElementById('accessoryControllerInfo');
    if (throttleControllerId) {
      const conn = data.connections.find(c => c.id === throttleControllerId);
      throttleInfo.textContent = conn ? (conn.id + ' (' + conn.systemType + ')') : throttleControllerId;
    } else {
      throttleInfo.textContent = 'No controller assigned';
    }
    if (accessoryControllerId) {
      const conn = data.connections.find(c => c.id === accessoryControllerId);
      accessoryInfo.textContent = conn ? (conn.id + ' (' + conn.systemType + ')') : accessoryControllerId;
    } else {
      accessoryInfo.textContent = 'No controller assigned';
    }
  } catch (err) {
    document.getElementById('connectionsList').innerHTML = '<p>Error loading connections</p>';
  }
}

let throttleState = { speed: 0, forward: true, functions: {} };
let isDraggingThrottle = false; // Track if user is actively dragging the throttle slider

async function getThrottleStatus() {
  const address = parseInt(document.getElementById('throttleAddress').value);
  const longAddress = document.getElementById('throttleLongAddress').checked;
  if (!address) {
    showStatus('Please enter an address', 'error');
    return;
  }
  if (!ws || ws.readyState !== WebSocket.OPEN) {
    showStatus('WebSocket not connected', 'error');
    return;
  }
  try {
    const message = {
      id: 'get-throttle-' + Date.now(),
      type: 'throttle',
      data: {
        address: address,
        longAddress: longAddress
      }
    };
    ws.send(JSON.stringify(message));
    updateThrottleStatus('Querying throttle status for address ' + address);
  } catch (err) {
    showStatus('Error: ' + err.message, 'error');
  }
}

function initThrottleFunctions() {
  const container = document.getElementById('throttleFunctions');
  container.innerHTML = '';
  for (let i = 0; i <= 12; i++) {
    const btn = document.createElement('button');
    btn.id = 'funcBtn' + i;
    btn.textContent = 'F' + i;
    if (i === 2) {
      // F2 is momentary - use mousedown/mouseup
      btn.onmousedown = () => setFunction(i, true);
      btn.onmouseup = () => setFunction(i, false);
      btn.onmouseleave = () => setFunction(i, false);
    } else {
      // Other functions are toggle
      btn.onclick = () => toggleFunction(i);
    }
    container.appendChild(btn);
  }
}

async function toggleFunction(funcNum) {
  const currentState = throttleState.functions[funcNum] || false;
  await setFunction(funcNum, !currentState);
}

async function setFunction(funcNum, on) {
  const address = parseInt(document.getElementById('throttleAddress').value);
  const longAddress = document.getElementById('throttleLongAddress').checked;
  if (!address || !ws || ws.readyState !== WebSocket.OPEN) return;
  
  try {
    const message = {
      id: 'throttle-func-' + Date.now(),
      type: 'throttle',
      method: 'post',
      data: {
        address: address,
        longAddress: longAddress,
        functions: {
          [String(funcNum)]: on
        }
      }
    };
    ws.send(JSON.stringify(message));
    throttleState.functions[funcNum] = on;
    updateFunctionButton(funcNum, on);
  } catch (err) {
    console.error('Error setting function:', err);
  }
}

function updateFunctionButton(funcNum, on) {
  const btn = document.getElementById('funcBtn' + funcNum);
  if (btn) {
    if (on) {
      btn.style.background = '#0e639c';
      btn.style.color = 'white';
    } else {
      btn.style.background = '#3e3e42';
      btn.style.color = '#cccccc';
    }
  }
}

async function toggleDirection() {
  const address = parseInt(document.getElementById('throttleAddress').value);
  const longAddress = document.getElementById('throttleLongAddress').checked;
  if (!address || !ws || ws.readyState !== WebSocket.OPEN) return;
  
  const newForward = !throttleState.forward;
  try {
    const message = {
      id: 'throttle-dir-' + Date.now(),
      type: 'throttle',
      method: 'post',
      data: {
        address: address,
        longAddress: longAddress,
        forward: newForward
      }
    };
    ws.send(JSON.stringify(message));
    throttleState.forward = newForward;
    const btn = document.getElementById('throttleDirectionBtn');
    btn.textContent = newForward ? 'Forward' : 'Reverse';
  } catch (err) {
    console.error('Error setting direction:', err);
  }
}

async function updateThrottleSpeed(value) {
  const address = parseInt(document.getElementById('throttleAddress').value);
  const longAddress = document.getElementById('throttleLongAddress').checked;
  if (!address || !ws || ws.readyState !== WebSocket.OPEN) return;
  
  const speedPercent = parseInt(value);
  document.getElementById('throttleSpeedValue').textContent = speedPercent + '%';
  const speedNormalized = speedPercent / 100.0;
  try {
    const message = {
      id: 'throttle-speed-' + Date.now(),
      type: 'throttle',
      method: 'post',
      data: {
        address: address,
        longAddress: longAddress,
        speed: speedNormalized
      }
    };
    ws.send(JSON.stringify(message));
    throttleState.speed = speedNormalized;
  } catch (err) {
    console.error('Error setting speed:', err);
  }
}

// Track when user starts/stops dragging the throttle slider
function setupThrottleSliderDragTracking() {
  // Wait for DOM to be ready
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', setupThrottleSliderDragTracking);
    return;
  }
  
  const slider = document.getElementById('throttleSpeed');
  if (!slider) {
    // Retry after a short delay if element doesn't exist yet
    setTimeout(setupThrottleSliderDragTracking, 100);
    return;
  }
  
  // Mouse events
  slider.addEventListener('mousedown', () => {
    isDraggingThrottle = true;
  });
  slider.addEventListener('mouseup', () => {
    isDraggingThrottle = false;
  });
  slider.addEventListener('mouseleave', () => {
    isDraggingThrottle = false;
  });
  
  // Touch events for mobile
  slider.addEventListener('touchstart', () => {
    isDraggingThrottle = true;
  });
  slider.addEventListener('touchend', () => {
    isDraggingThrottle = false;
  });
  slider.addEventListener('touchcancel', () => {
    isDraggingThrottle = false;
  });
}

function updateThrottleStatus(message) {
  const statusDiv = document.getElementById('throttleStatus');
  const time = new Date().toLocaleTimeString();
  statusDiv.textContent = '[' + time + '] ' + message;
}

async function setAccessory(closed) {
  const address = parseInt(document.getElementById('accessoryAddress').value);
  if (!address) {
    showStatus('Please enter an address', 'error');
    return;
  }
  if (!ws || ws.readyState !== WebSocket.OPEN) {
    showStatus('WebSocket not connected', 'error');
    return;
  }
  try {
    const state = closed ? 'closed' : 'thrown';
    const message = {
      id: 'accessory-' + Date.now(),
      type: 'accessories',
      method: 'post',
      data: {
        commands: [
          {
            address: address,
            state: state
          }
        ]
      }
    };
    ws.send(JSON.stringify(message));
    updateAccessoryStatus('Accessory ' + address + ' set to ' + (closed ? 'CLOSED' : 'THROWN'));
  } catch (err) {
    showStatus('Error: ' + err.message, 'error');
  }
}

function updateAccessoryStatus(message) {
  const statusDiv = document.getElementById('accessoryStatus');
  const time = new Date().toLocaleTimeString();
  statusDiv.textContent = '[' + time + '] ' + message;
}

function showStatus(msg, type) {
  const div = document.getElementById('status');
  div.className = 'status ' + type;
  div.textContent = msg;
  setTimeout(() => div.textContent = '', 5000);
}

async function requestVersion(connectionId) {
  try {
    const res = await fetch('/connections/requestVersion?id=' + encodeURIComponent(connectionId), { method: 'POST' });
    const data = await res.json();
    if (res.ok) {
      showStatus('Version request sent. Waiting for response...', 'success');
      // Reload connections after a short delay to show updated version
      setTimeout(() => loadConnections(), 1000);
    } else {
      showStatus('Error: ' + (data.error || 'Unknown error'), 'error');
    }
  } catch (err) {
    showStatus('Error: ' + err.message, 'error');
  }
}

let eventSource = null;
let connectionPowerStatus = {};
let ws = null;
let wsReconnectTimer = null;

function connectEventStream() {
  if (eventSource) {
    eventSource.close();
  }
  eventSource = new EventSource('/api/events');
  eventSource.onmessage = function(event) {
    try {
      const data = JSON.parse(event.data);
      handleEvent(data);
    } catch (e) {
      console.error('Error parsing event:', e);
    }
  };
  eventSource.onerror = function(e) {
    console.error('EventSource error:', e);
    // Try to reconnect after 3 seconds
    setTimeout(connectEventStream, 3000);
  };
}

function handleEvent(data) {
  if (data.type === 'MESSAGE_RECEIVED' || data.type === 'MESSAGE_SENT') {
    const decoded = data.payload.decoded || data.payload.message || '';
    const hex = data.payload.hex || '';
    let messageText = `[${data.connectionId}] ${data.payload.direction.toUpperCase()}: `;
    if (decoded && decoded !== data.payload.message) {
      messageText += decoded;
      if (hex) messageText += ` (${hex})`;
    } else {
      messageText += data.payload.message || hex || '';
      if (hex && data.payload.message && hex !== data.payload.message) messageText += ` (${hex})`;
    }
    addConsoleLine(data.type === 'MESSAGE_RECEIVED' ? 'in' : 'out', messageText);
    // If this looks like a version response, reload connections to show updated info
    if (data.type === 'MESSAGE_RECEIVED' && decoded && (decoded.includes('Software Version') || decoded.includes('CS Version') || (hex && hex.includes('63 21')))) {
      setTimeout(() => loadConnections(), 500);
    }
  } else if (data.type === 'THROTTLE_UPDATED') {
    const desc = data.payload.description || `${data.payload.property} = ${data.payload.newValue}`;
    addConsoleLine('power', `[${data.connectionId}] ${desc}`);
    // Update throttle UI if this is for the current throttle
    if (currentThrottleId && currentThrottleId.startsWith(data.connectionId + ':')) {
      const address = data.payload.address;
      const throttleAddress = parseInt(document.getElementById('throttleAddress').value);
      if (address === throttleAddress) {
        updateThrottleStatus(desc);
        const prop = data.payload.property;
        const newVal = data.payload.newValue;
        if (prop === 'SpeedSetting') {
          throttleState.speed = newVal;
          const speedPercent = Math.round(newVal * 100);
          document.getElementById('throttleSpeed').value = speedPercent;
          document.getElementById('throttleSpeedValue').textContent = speedPercent + '%';
        } else if (prop === 'IsForward') {
          throttleState.forward = newVal;
          const btn = document.getElementById('throttleDirectionBtn');
          btn.textContent = newVal ? 'Forward' : 'Reverse';
        } else if (prop && prop.startsWith('F') && !prop.endsWith('Momentary')) {
          const funcNum = parseInt(prop.substring(1));
          if (!isNaN(funcNum)) {
            throttleState.functions[funcNum] = newVal;
            updateFunctionButton(funcNum, newVal);
          }
        }
      }
    }
  } else if (data.type === 'POWER_CHANGED') {
    const connId = data.connectionId;
    const status = data.payload.status || 'UNKNOWN';
    connectionPowerStatus[connId] = status;
    addConsoleLine('power', `[${connId}] Power: ${status}`);
    updateConnectionPowerStatus(connId, status);
  } else if (data.type === 'COMMUNICATION_ERROR') {
    addConsoleLine('error', `[${data.connectionId}] Error: ${data.payload.message || 'Communication error'}`);
  } else if (data.type === 'CONNECTION_STATE_CHANGED') {
    addConsoleLine('power', `[${data.connectionId}] Connection: ${data.payload.connected ? 'Connected' : 'Disconnected'}`);
  }
}

function addConsoleLine(className, message) {
  const console = document.getElementById('consoleOutput');
  const line = document.createElement('div');
  line.className = 'console-line ' + className;
  const timestamp = new Date().toLocaleTimeString();
  line.innerHTML = `<span class="console-timestamp">${timestamp}</span>${message}`;
  console.appendChild(line);
  // Auto-scroll to bottom
  console.scrollTop = console.scrollHeight;
  // Limit to 500 lines to prevent memory issues
  while (console.children.length > 500) {
    console.removeChild(console.firstChild);
  }
}

function updateConnectionPowerStatus(connId, status) {
  // Update power status in connections list
  const connections = document.querySelectorAll('.connection-item');
  connections.forEach(item => {
    if (item.textContent.includes(connId)) {
      // Find and update power status in this connection item
      const powerElement = item.querySelector('.power-status');
      if (powerElement) {
        const powerIcon = status === 'ON' ? '🟢' : status === 'OFF' ? '🔴' : '🟡';
        powerElement.textContent = `Power: ${powerIcon} ${status}`;
        // Update the power button next to it
        const powerButton = powerElement.nextElementSibling;
        if (powerButton && powerButton.tagName === 'BUTTON') {
          const isOn = status === 'ON';
          powerButton.textContent = `Power ${isOn ? 'OFF' : 'ON'}`;
          powerButton.className = `btn btn-${isOn ? 'danger' : 'primary'}`;
          powerButton.onclick = () => setPower(connId, isOn ? 'OFF' : 'ON');
        }
      }
    }
  });
}

async function setControllerRole(connectionId, role, enabled) {
  try {
    const res = await fetch('/connections/setRole?connectionId=' + encodeURIComponent(connectionId) + '&role=' + role + '&enabled=' + enabled, { method: 'POST' });
    const data = await res.json();
    if (res.ok) {
      showStatus('Controller role updated', 'success');
      loadConnections();
    } else {
      showStatus('Error: ' + (data.error || 'Unknown error'), 'error');
      loadConnections(); // Reload to reset checkboxes
    }
  } catch (err) {
    showStatus('Error: ' + err.message, 'error');
    loadConnections(); // Reload to reset checkboxes
  }
}

async function setPower(connectionId, powerState) {
  if (!ws || ws.readyState !== WebSocket.OPEN) {
    showStatus('WebSocket not connected', 'error');
    return;
  }
  try {
    const message = {
      id: 'power-' + connectionId + '-' + Date.now(),
      type: 'status',
      method: 'post',
      data: {
        connectionId: connectionId,
        power: powerState
      }
    };
    ws.send(JSON.stringify(message));
    showStatus(`Setting power to ${powerState} for ${connectionId}...`, 'success');
  } catch (err) {
    showStatus('Error: ' + err.message, 'error');
  }
}

async function discoverDevices() {
  const statusDiv = document.getElementById('discoverStatus');
  const devicesDiv = document.getElementById('discoveredDevices');
  statusDiv.textContent = 'Scanning for controllers...';
  devicesDiv.style.display = 'none';
  try {
    const res = await fetch('/api/discover');
    const data = await res.json();
    if (res.ok) {
      if (data.devices && data.devices.length > 0) {
        statusDiv.textContent = 'Found ' + data.devices.length + ' controller(s)';
        devicesDiv.innerHTML = '<div style="margin-top: 10px;"><strong>Detected Controllers:</strong></div>';
        data.devices.forEach(device => {
          const deviceDiv = document.createElement('div');
          deviceDiv.style.cssText = 'margin: 5px 0; padding: 8px; background: white; border-radius: 3px; border: 1px solid #ddd;';
          deviceDiv.innerHTML = '<strong>' + device.description + '</strong> (' + device.systemType + ')<br>' +
            '<small style="color: #666;">Port: ' + device.port + ' | VID:PID ' + device.vendorId + ':' + device.productId + '</small><br>' +
            '<button onclick="useDiscoveredDevice(\'' + device.port + '\', \'' + device.systemType + '\')" style="margin-top: 5px; padding: 5px 10px; background: #0e639c; color: white; border: none; border-radius: 3px; cursor: pointer; font-size: 12px;">Use This Device</button>';
          devicesDiv.appendChild(deviceDiv);
        });
        devicesDiv.style.display = 'block';
      } else {
        statusDiv.textContent = 'No controllers detected';
      }
    } else {
      statusDiv.textContent = 'Error: ' + (data.error || 'Unknown error');
    }
  } catch (err) {
    statusDiv.textContent = 'Error: ' + err.message;
  }
}

function useDiscoveredDevice(port, systemType) {
  document.getElementById('portName').value = port;
  document.getElementById('systemType').value = systemType;
  // Trigger change event to show/hide appropriate fields
  document.getElementById('systemType').dispatchEvent(new Event('change'));
  showStatus('Device selected: ' + port, 'success');
}

function buildWsUrl() {
  const loc = window.location;
  const protocol = loc.protocol === 'https:' ? 'wss:' : 'ws:';
  const port = loc.port ? ':' + (parseInt(loc.port, 10) + 1) : '';
  return `${protocol}//${loc.hostname}${port}/json`;
}

function connectWebSocket() {
  const statusEl = document.getElementById('wsStatus');
  const url = buildWsUrl();
  if (ws) {
    ws.onopen = null;
    ws.onclose = null;
    ws.onmessage = null;
    ws.onerror = null;
    ws.close();
  }
  ws = new WebSocket(url);
  statusEl.textContent = 'Connecting...';
  statusEl.classList.remove('connected');

  ws.onopen = () => {
    statusEl.textContent = 'Connected';
    statusEl.classList.add('connected');
    addWsMessage('Connected to ' + url, 'info');
  };

  ws.onclose = () => {
    statusEl.textContent = 'Disconnected';
    statusEl.classList.remove('connected');
    addWsMessage('Disconnected', 'error');
    if (wsReconnectTimer) {
      clearTimeout(wsReconnectTimer);
    }
    wsReconnectTimer = setTimeout(connectWebSocket, 3000);
  };

  ws.onerror = (err) => {
    addWsMessage('WebSocket error: ' + (err.message || ''), 'error');
  };

  ws.onmessage = (evt) => {
    try {
      const parsed = JSON.parse(evt.data);
      addWsMessage(JSON.stringify(parsed, null, 2), 'in');
      applyWsDelta(parsed);
      // Handle direct responses to power control requests
      if (parsed.id && parsed.id.startsWith('power-')) {
        if (parsed.type === 'status' && parsed.data) {
          const connections = parsed.data.connections || [];
          connections.forEach(conn => {
            if (conn.powerStatus) {
              connectionPowerStatus[conn.id] = conn.powerStatus;
              updateConnectionPowerStatus(conn.id, conn.powerStatus);
              showStatus(`Power set to ${conn.powerStatus} for ${conn.id}`, 'success');
            }
          });
        } else if (parsed.type === 'error') {
          const errorMsg = parsed.data?.message || 'Unknown error';
          showStatus(`Power control error: ${errorMsg}`, 'error');
        }
      }
    } catch (e) {
      addWsMessage(evt.data, 'in');
    }
  };
}

function manualReconnectWs() {
  if (wsReconnectTimer) {
    clearTimeout(wsReconnectTimer);
    wsReconnectTimer = null;
  }
  connectWebSocket();
}

function addWsMessage(message, cls) {
  const container = document.getElementById('wsMessages');
  const line = document.createElement('div');
  line.className = 'ws-msg ' + (cls || '');
  const ts = new Date().toLocaleTimeString();
  line.innerHTML = `<span class="ws-ts">${ts}</span>${escapeHtml(message)}`;
  container.appendChild(line);
  container.scrollTop = container.scrollHeight;
  while (container.children.length > 300) {
    container.removeChild(container.firstChild);
  }
}

function sendWs() {
  const input = document.getElementById('wsInput');
  const text = input.value.trim();
  if (!text) {
    addWsMessage('Nothing to send', 'error');
    return;
  }
  if (!ws || ws.readyState !== WebSocket.OPEN) {
    addWsMessage('WebSocket not connected', 'error');
    return;
  }
  try {
    JSON.parse(text);
  } catch (e) {
    addWsMessage('Invalid JSON: ' + e.message, 'error');
    return;
  }
  ws.send(text);
  addWsMessage(text, 'out');
}

function escapeHtml(str) {
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

function applyWsDelta(msg) {
  if (!msg || !msg.type) return;
  if (msg.type === 'status') {
    // Handle status patch broadcasts (power changes, etc.)
    if (msg.method === 'patch' && msg.data && msg.data.connections) {
      // Update power status for changed connections
      msg.data.connections.forEach(conn => {
        if (conn.powerStatus) {
          connectionPowerStatus[conn.id] = conn.powerStatus;
          updateConnectionPowerStatus(conn.id, conn.powerStatus);
        }
      });
    }
    // refresh connections view on status broadcast
    loadConnections();
  } else if (msg.type === 'throttle') {
    const currentAddress = parseInt(document.getElementById('throttleAddress').value);
    const currentLongAddress = document.getElementById('throttleLongAddress').checked;
    
    if (msg.method === 'patch' && msg.data) {
      // Check if this update is for the current address
      if (msg.data.address === currentAddress && msg.data.longAddress === currentLongAddress) {
        // Ignore speed patches while user is actively dragging the slider
        if (msg.data.speed !== undefined && !isDraggingThrottle) {
          throttleState.speed = msg.data.speed;
          const percent = Math.round(msg.data.speed * 100);
          document.getElementById('throttleSpeed').value = percent;
          document.getElementById('throttleSpeedValue').textContent = percent + '%';
        }
        if (msg.data.forward !== undefined) {
          throttleState.forward = msg.data.forward;
          document.getElementById('throttleDirectionBtn').textContent = msg.data.forward ? 'Forward' : 'Reverse';
        }
        if (msg.data.functions) {
          Object.keys(msg.data.functions).forEach(fn => {
            const on = msg.data.functions[fn];
            throttleState.functions[fn] = on;
            updateFunctionButton(parseInt(fn, 10), on);
          });
        }
      }
    } else if (!msg.method && msg.data && msg.data.address === currentAddress && msg.data.longAddress === currentLongAddress) {
      // GET response - update UI with current state
      if (msg.data.speed !== undefined) {
        throttleState.speed = msg.data.speed;
        const percent = Math.round(msg.data.speed * 100);
        document.getElementById('throttleSpeed').value = percent;
        document.getElementById('throttleSpeedValue').textContent = percent + '%';
      }
      if (msg.data.forward !== undefined) {
        throttleState.forward = msg.data.forward;
        document.getElementById('throttleDirectionBtn').textContent = msg.data.forward ? 'Forward' : 'Reverse';
      }
      if (msg.data.functions) {
        Object.keys(msg.data.functions).forEach(fn => {
          const on = msg.data.functions[fn];
          throttleState.functions[parseInt(fn, 10)] = on;
          updateFunctionButton(parseInt(fn, 10), on);
        });
      }
      updateThrottleStatus('Throttle status for address ' + currentAddress);
    }
  } else if (msg.type === 'accessories' && msg.method === 'patch' && msg.data && msg.data.length > 0) {
    const first = msg.data[0];
    if (first && first.name && first.state) {
      updateAccessoryStatus(first.name + ' -> ' + first.state);
    }
  }
}

function loadExampleMessage() {
  const select = document.getElementById('wsExampleSelect');
  const input = document.getElementById('wsInput');
  const value = select.value;
  if (!value) {
    input.value = '';
    return;
  }
  
  let example = '';
  switch (value) {
    case 'status':
      example = JSON.stringify({ 
        id: 'req-1',
        type: 'status' 
      }, null, 2);
      break;
    case 'throttle-get':
      example = JSON.stringify({
        id: 'req-2',
        type: 'throttle',
        data: {
          address: 3
        }
      }, null, 2);
      break;
    case 'throttle-post':
      example = JSON.stringify({
        id: 'req-3',
        type: 'throttle',
        method: 'post',
        data: {
          address: 3,
          speed: 0.6,
          forward: true,
          functions: {
            "0": true,
            "1": true,
            "2": false
          }
        }
      }, null, 2);
      break;
    case 'throttles-list':
      example = JSON.stringify({ 
        id: 'req-5',
        list: 'throttles' 
      }, null, 2);
      break;
    case 'accessories-post':
      example = JSON.stringify({
        id: 'req-6',
        type: 'accessories',
        method: 'post',
        data: {
          accessories: [
            { name: 'signal1', state: 'green' },
            { name: 'point1', state: 'thrown' }
          ],
          commands: [
            { address: 12, state: 'closed' },
            { address: 1, state: 'thrown' }
          ]
        }
      }, null, 2);
      break;
    case 'accessories-get':
      example = JSON.stringify({
        id: 'req-7',
        type: 'accessories',
        data: {
          name: 'signal1'
        }
      }, null, 2);
      break;
    case 'accessories-list':
      example = JSON.stringify({ 
        id: 'req-8',
        list: 'accessories' 
      }, null, 2);
      break;
    case 'power-post':
      example = JSON.stringify({
        id: 'req-9',
        type: 'status',
        method: 'post',
        data: {
          connectionId: 'elite1',
          power: 'OFF'
        }
      }, null, 2);
      break;
  }
  input.value = example;
  select.value = ''; // Reset dropdown after selection
}

let updatePollInterval = null;

async function loadUpdateStatus() {
  try {
    const res = await fetch('/api/update');
    const data = await res.json();
    const installed = document.getElementById('installedVersion');
    const statusEl = document.getElementById('updateState');
    const msgEl = document.getElementById('updateMessage');
    const btnInstall = document.getElementById('btnInstallUpdate');
    const btnCheck = document.getElementById('btnCheckUpdate');
    if (installed) {
      installed.textContent = data.currentVersion != null ? data.currentVersion : '—';
    }
    if (statusEl) {
      statusEl.textContent = data.state != null ? data.state : '—';
    }
    if (msgEl) {
      msgEl.textContent = data.message != null ? data.message : '';
    }
    if (btnInstall) {
      const busy = ['checking', 'downloading', 'installing'].includes(data.state);
      btnInstall.disabled = !data.hasUpdate || busy;
    }
    if (btnCheck) {
      const busy = ['checking', 'downloading', 'installing'].includes(data.state);
      btnCheck.disabled = busy;
    }
    const busy = ['checking', 'downloading', 'installing'].includes(data.state);
    if (busy && !updatePollInterval) {
      updatePollInterval = setInterval(loadUpdateStatus, 2000);
    } else if (!busy && updatePollInterval) {
      clearInterval(updatePollInterval);
      updatePollInterval = null;
    }
  } catch (err) {
    console.error('loadUpdateStatus:', err);
  }
}

async function checkForUpdates() {
  const btn = document.getElementById('btnCheckUpdate');
  if (btn) btn.disabled = true;
  try {
    const res = await fetch('/api/update/check', { method: 'POST' });
    const data = await res.json();
    if (!res.ok) {
      throw new Error(data.error || res.statusText);
    }
    await loadUpdateStatus();
  } catch (e) {
    showStatus('Update check failed: ' + e.message, 'error');
  } finally {
    const btn2 = document.getElementById('btnCheckUpdate');
    if (btn2) btn2.disabled = false;
    loadUpdateStatus();
  }
}

async function installUpdate() {
  if (!confirm('Install the latest update? The service may restart.')) {
    return;
  }
  const btn = document.getElementById('btnInstallUpdate');
  if (btn) btn.disabled = true;
  try {
    const res = await fetch('/api/update/install', { method: 'POST' });
    const data = await res.json();
    if (!res.ok) {
      throw new Error(data.error || res.statusText);
    }
    await loadUpdateStatus();
    showStatus('Update finished.', 'success');
  } catch (e) {
    showStatus('Update failed: ' + e.message, 'error');
  } finally {
    const btn2 = document.getElementById('btnInstallUpdate');
    if (btn2) btn2.disabled = false;
    loadUpdateStatus();
  }
}

document.getElementById('btnCheckUpdate')?.addEventListener('click', checkForUpdates);
document.getElementById('btnInstallUpdate')?.addEventListener('click', installUpdate);

// Initialize on page load
loadSystems();
loadPorts();
loadConnections();
loadUpdateStatus();
setInterval(loadConnections, 5000);
setInterval(loadPorts, 5000);
setInterval(loadUpdateStatus, 15000);
connectEventStream();
connectWebSocket();
initThrottleFunctions();
setupThrottleSliderDragTracking();

