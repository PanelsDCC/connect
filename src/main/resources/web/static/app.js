let systems = [];
let ports = [];

const PAGE_IDS = ['dcc', 'sensors', 'wifi', 'updates'];

function parseLocationHash() {
  const raw = (window.location.hash || '#dcc').replace(/^#/, '');
  const parts = raw.split('/').filter(Boolean);
  const page = PAGE_IDS.includes(parts[0]) ? parts[0] : 'dcc';
  return { page, parts, raw };
}

function ensureDefaultPageHash() {
  const { page, raw } = parseLocationHash();
  if (!PAGE_IDS.includes(page)) {
    window.history.replaceState(null, '', '#dcc');
  } else if (!raw) {
    window.history.replaceState(null, '', '#dcc');
  }
}

function currentPageFromHash() {
  return parseLocationHash().page;
}

function updatePageSectionState() {
  const current = currentPageFromHash();
  document.querySelectorAll('.page-section').forEach((section) => {
    const isActive = section.getAttribute('data-page') === current;
    section.classList.toggle('is-active', isActive);
  });
  if (current === 'sensors' && typeof syncNodesScreenFromHash === 'function') {
    syncNodesScreenFromHash();
  }
}

function updatePageNavState() {
  const current = currentPageFromHash();
  document.querySelectorAll('[data-page-link]').forEach((link) => {
    const isActive = link.getAttribute('data-page-link') === current;
    link.classList.toggle('is-active', isActive);
  });
}

function setPageHash(hash) {
  const next = hash.startsWith('#') ? hash : '#' + hash;
  if (window.location.hash === next) {
    updatePageSectionState();
    updatePageNavState();
    return;
  }
  window.history.pushState(null, '', next);
  updatePageSectionState();
  updatePageNavState();
}

function setupHashNavigation() {
  document.querySelectorAll('[data-page-link]').forEach((link) => {
    link.addEventListener('click', (event) => {
      event.preventDefault();
      const targetHash = '#' + link.getAttribute('data-page-link');
      setPageHash(targetHash);
    });
  });
  window.addEventListener('hashchange', () => {
    ensureDefaultPageHash();
    updatePageSectionState();
    updatePageNavState();
  });
}

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

function updateTransportFields() {
  const systemId = document.getElementById('systemType').value;
  const system = systems.find(s => s.id === systemId);
  const portGroup = document.getElementById('portGroup');
  const serialOpts = document.getElementById('serialOptionsGroup');
  const networkGroup = document.getElementById('networkGroup');
  const dccppTransportGroup = document.getElementById('dccppTransportGroup');
  const baudRateSelect = document.getElementById('baudRate');

  if (!system) {
    portGroup.style.display = 'block';
    serialOpts.style.display = 'none';
    networkGroup.style.display = 'none';
    dccppTransportGroup.style.display = 'none';
    return;
  }

  const hasNet = system.connectionTypes.includes('network');
  const hasSerial = system.connectionTypes.includes('serial') || system.connectionTypes.includes('usb');
  const hybrid = hasNet && hasSerial;
  dccppTransportGroup.style.display = hybrid ? 'block' : 'none';

  let useEthernet = hasNet && !hasSerial;
  if (hybrid) {
    const checked = document.querySelector('input[name="dccppTransport"]:checked');
    useEthernet = checked && checked.value === 'ethernet';
  }

  if (useEthernet) {
    portGroup.style.display = 'none';
    serialOpts.style.display = 'none';
    networkGroup.style.display = 'block';
  } else {
    portGroup.style.display = 'block';
    serialOpts.style.display = hasSerial ? 'block' : 'none';
    networkGroup.style.display = 'none';
    if (systemId === 'dccpp') {
      baudRateSelect.value = '115200';
    } else if (systemId === 'xnet-elite') {
      baudRateSelect.value = '19200';
    } else {
      baudRateSelect.value = '';
    }
  }
}

document.getElementById('systemType').addEventListener('change', () => updateTransportFields());

document.querySelectorAll('input[name="dccppTransport"]').forEach((el) => {
  el.addEventListener('change', () => updateTransportFields());
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
  const hasNet = system && system.connectionTypes.includes('network');
  const hasSerial = system && (system.connectionTypes.includes('serial') || system.connectionTypes.includes('usb'));
  const hybrid = hasNet && hasSerial;
  const useEthernet = hybrid
    ? (document.querySelector('input[name="dccppTransport"]:checked') || {}).value === 'ethernet'
    : hasNet && !hasSerial;

  if (useEthernet) {
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

/** Broadcast accessory address via WebSocket (CS if present + all nodes). Forward→closed/ON. */
function sendAccessoryAddress(address, closed) {
  const addr = Number(address);
  if (!addr || addr < 1) {
    showStatus('Invalid accessory address', 'error');
    return false;
  }
  if (!ws || ws.readyState !== WebSocket.OPEN) {
    showStatus('WebSocket not connected', 'error');
    return false;
  }
    const state = closed ? 'closed' : 'thrown';
  ws.send(JSON.stringify({
      id: 'accessory-' + Date.now(),
      type: 'accessories',
      method: 'post',
      data: {
      commands: [{ address: addr, state }]
    }
  }));
  updateAccessoryStatus('Accessory ' + addr + ' set to ' + (closed ? 'CLOSED' : 'THROWN'));
  return true;
}

async function setAccessory(closed) {
  const address = parseInt(document.getElementById('accessoryAddress').value);
  if (!address) {
    showStatus('Please enter an address', 'error');
    return;
  }
  try {
    sendAccessoryAddress(address, closed);
  } catch (err) {
    showStatus('Error: ' + err.message, 'error');
  }
}

function updateAccessoryStatus(message) {
  const statusDiv = document.getElementById('accessoryStatus');
  if (!statusDiv) return;
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
    if (data.type === 'MESSAGE_RECEIVED' && decoded && (
      decoded.includes('Software Version') ||
      decoded.includes('CS Version') ||
      decoded.includes('DCC-EX') ||
      decoded.includes('Station:') ||
      (hex && hex.includes('63 21'))
    )) {
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
        statusDiv.textContent = 'Found ' + data.devices.length + ' device(s)';
        devicesDiv.innerHTML = '<div style="margin-top: 10px;"><strong>Detected devices:</strong></div>';
        data.devices.forEach(device => {
          const deviceDiv = document.createElement('div');
          deviceDiv.style.cssText = 'margin: 5px 0; padding: 8px; background: white; border-radius: 3px; border: 1px solid #ddd;';
          const name = device.name || device.description;
          let actionHtml;
          if (device.systemType === 'panelsdcc-sensor-board') {
            actionHtml = '<a href="#sensors" class="btn btn-primary btn-small" style="display:inline-block;margin-top:5px;text-decoration:none;" onclick="setPageHash(\'#sensors\'); return false;">Open in Sensors</a>';
          } else if (device.systemType === 'usb-serial-candidate' || device.needsProbe) {
            const portEsc = String(device.port || '').replace(/\\/g, '\\\\').replace(/'/g, "\\'");
            actionHtml = '<button type="button" class="btn btn-primary btn-small" style="margin-top:5px;" onclick="probeSensorPort(\'' + portEsc + '\', this)">Probe now</button>' +
              ' <a href="#sensors" class="btn btn-secondary btn-small" style="display:inline-block;margin-top:5px;text-decoration:none;" onclick="setPageHash(\'#sensors\'); return false;">Sensors</a>';
          } else {
            actionHtml = '<button onclick="useDiscoveredDevice(\'' + device.port + '\', \'' + device.systemType + '\')" style="margin-top: 5px; padding: 5px 10px; background: #0e639c; color: white; border: none; border-radius: 3px; cursor: pointer; font-size: 12px;">Use This Device</button>';
          }
          deviceDiv.innerHTML = '<strong>' + name + '</strong> (' + device.systemType + ')<br>' +
            '<small style="color: #666;">Port: ' + device.port + ' | VID:PID ' + device.vendorId + ':' + device.productId +
            (device.config && device.config.boardPrefix ? ' | prefix ' + device.config.boardPrefix : '') +
            '</small><br>' + actionHtml;
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
  if (systemType === 'dccpp') {
    const serialRadio = document.querySelector('input[name="dccppTransport"][value="serial"]');
    if (serialRadio) serialRadio.checked = true;
  }
  document.getElementById('systemType').value = systemType;
  document.getElementById('systemType').dispatchEvent(new Event('change'));
  document.getElementById('portName').value = port;
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

/** Parse JSON from fetch; if body is not valid JSON, surface the raw text (avoids cryptic JSON.parse errors). */
async function readJsonOrError(res) {
  const text = await res.text();
  try {
    return JSON.parse(text);
  } catch (e) {
    const snippet = text.length > 400 ? text.slice(0, 400) + '…' : text;
    throw new Error(snippet || res.statusText || 'Invalid JSON response');
  }
}

async function loadUpdateStatus() {
  try {
    const res = await fetch('/api/update');
    const data = await readJsonOrError(res);
    const installed = document.getElementById('installedVersion');
    const statusEl = document.getElementById('updateState');
    const msgEl = document.getElementById('updateMessage');
    const btnInstall = document.getElementById('btnInstallUpdate');
    const btnCheck = document.getElementById('btnCheckUpdate');
    const btnLocal = document.getElementById('btnInstallLocalDeb');
    const fileInput = document.getElementById('updateDebFile');
    if (installed) {
      installed.textContent = data.currentVersion != null ? data.currentVersion : '—';
    }
    if (statusEl) {
      statusEl.textContent = data.state != null ? data.state : '—';
    }
    if (msgEl) {
      msgEl.textContent = data.message != null ? data.message : '';
    }
    const busy = ['checking', 'downloading', 'installing'].includes(data.state);
    if (btnInstall) {
      btnInstall.disabled = !data.hasUpdate || busy;
    }
    if (btnCheck) {
      btnCheck.disabled = busy;
    }
    if (fileInput) {
      fileInput.disabled = busy;
    }
    if (btnLocal) {
      btnLocal.disabled = busy;
    }
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
    const data = await readJsonOrError(res);
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
    const data = await readJsonOrError(res);
    if (!res.ok) {
      throw new Error(data.error || res.statusText);
    }
    await loadUpdateStatus();
    if (res.status === 202) {
      showStatus(data.message || 'Install started. Watch status below; the page may reconnect when the service restarts.', 'success');
    } else {
      showStatus('Update finished.', 'success');
    }
  } catch (e) {
    showStatus('Update failed: ' + e.message, 'error');
  } finally {
    const btn2 = document.getElementById('btnInstallUpdate');
    if (btn2) btn2.disabled = false;
    loadUpdateStatus();
  }
}

function openLocalDebPicker() {
  const fileInput = document.getElementById('updateDebFile');
  if (!fileInput || fileInput.disabled) return;
  fileInput.value = '';
  fileInput.click();
}

async function installLocalDebFromFile(file) {
  if (!file) return;
  if (!file.name.toLowerCase().endsWith('.deb')) {
    showStatus('File must be a .deb package.', 'error');
    return;
  }
  if (!confirm('Install ' + file.name + '? The service may restart.')) {
    return;
  }
  const btn = document.getElementById('btnInstallLocalDeb');
  const fileInput = document.getElementById('updateDebFile');
  if (btn) btn.disabled = true;
  if (fileInput) fileInput.disabled = true;
  try {
    const url = '/api/update/upload?filename=' + encodeURIComponent(file.name);
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/octet-stream' },
      body: file
    });
    const data = await readJsonOrError(res);
    if (!res.ok) {
      throw new Error(data.error || res.statusText);
    }
    if (fileInput) fileInput.value = '';
    await loadUpdateStatus();
    showStatus(data.message || 'Local install started. Watch status below; the page may reconnect when the service restarts.', 'success');
  } catch (e) {
    showStatus('Local install failed: ' + e.message, 'error');
  } finally {
    loadUpdateStatus();
  }
}

document.getElementById('btnCheckUpdate')?.addEventListener('click', checkForUpdates);
document.getElementById('btnInstallUpdate')?.addEventListener('click', installUpdate);
document.getElementById('btnInstallLocalDeb')?.addEventListener('click', openLocalDebPicker);
document.getElementById('updateDebFile')?.addEventListener('change', function () {
  const file = this.files && this.files[0];
  if (file) installLocalDebFromFile(file);
});

function buildWifiQrPayload(ssid, password) {
  if (!ssid || !password) {
    return '';
  }
  const esc = (value) => String(value).replace(/([\\;,:"])/g, '\\$1');
  return `WIFI:S:${esc(ssid)};T:WPA;P:${esc(password)};H:false;;`;
}

function renderWifiQr(ssid, password) {
  const img = document.getElementById('wifiQrImage');
  const hint = document.getElementById('wifiQrHint');
  if (!img || !hint) return;
  if (!ssid || !password) {
    img.style.display = 'none';
    img.removeAttribute('src');
    hint.textContent = 'QR appears when hotspot credentials are available.';
    return;
  }
  const payload = buildWifiQrPayload(ssid, password);
  img.src = 'https://api.qrserver.com/v1/create-qr-code/?size=220x220&data=' + encodeURIComponent(payload);
  img.style.display = 'block';
  hint.textContent = 'Scan with a phone camera to join the hotspot.';
}

function renderWifiMode(mode) {
  const modeEl = document.getElementById('wifiMode');
  const btnClient = document.getElementById('btnWifiModeClient');
  const btnHotspot = document.getElementById('btnWifiModeHotspot');
  const btnFallback = document.getElementById('btnWifiApplyFallback');
  if (!modeEl || !btnClient || !btnHotspot || !btnFallback) return;

  const normalized = (mode || 'unknown').toLowerCase();
  modeEl.classList.remove('mode-client', 'mode-hotspot', 'mode-unknown');
  if (normalized === 'client') {
    modeEl.textContent = 'Client';
    modeEl.classList.add('mode-client');
    btnClient.classList.add('is-hidden');
    btnHotspot.classList.remove('is-hidden');
    btnFallback.classList.remove('is-hidden');
  } else if (normalized === 'hotspot') {
    modeEl.textContent = 'Hotspot';
    modeEl.classList.add('mode-hotspot');
    btnHotspot.classList.add('is-hidden');
    btnClient.classList.remove('is-hidden');
    btnFallback.classList.add('is-hidden');
  } else {
    modeEl.textContent = mode || '—';
    modeEl.classList.add('mode-unknown');
    btnClient.classList.remove('is-hidden');
    btnHotspot.classList.remove('is-hidden');
    btnFallback.classList.remove('is-hidden');
  }
}

function renderWifiStatusContext(data) {
  const mode = (data?.mode || '').toLowerCase();
  const stateLabel = document.getElementById('wifiStateLabel');
  const stateValue = document.getElementById('wifiState');
  const connectionLabel = document.getElementById('wifiConnectionLabel');
  const connectionValue = document.getElementById('wifiConnection');
  const fallbackRow = document.getElementById('wifiFallbackRow');
  if (!stateLabel || !stateValue || !connectionLabel || !connectionValue) return;

  if (mode === 'hotspot') {
    stateLabel.textContent = 'Hotspot status:';
    stateValue.textContent = 'Active';
    connectionLabel.textContent = 'Hotspot network:';
    connectionValue.textContent = data.hotspot?.ssid || '—';
    if (fallbackRow) fallbackRow.classList.add('is-hidden');
    return;
  }

  if (mode === 'client') {
    stateLabel.textContent = 'Client link state:';
    stateValue.textContent = data.wifiState || 'Not connected';
    connectionLabel.textContent = 'Connected network:';
    connectionValue.textContent = data.activeWifiConnection || data.wifiConnection || 'Not connected';
    if (fallbackRow) fallbackRow.classList.remove('is-hidden');
    return;
  }

  stateLabel.textContent = 'WiFi state:';
  stateValue.textContent = data.wifiState || '—';
  connectionLabel.textContent = 'Connected network:';
  connectionValue.textContent = data.activeWifiConnection || data.wifiConnection || '—';
  if (fallbackRow) fallbackRow.classList.remove('is-hidden');
}

function renderKnownNetworks(networks, activeWifiConnection, mode) {
  const container = document.getElementById('wifiKnownNetworks');
  const hint = document.getElementById('wifiKnownHint');
  if (!container || !hint) return;

  if (!Array.isArray(networks) || networks.length === 0) {
    hint.textContent = 'No saved networks found yet.';
    container.innerHTML = '<div class="wifi-network-meta">No known networks.</div>';
    return;
  }

  const hotspotMode = (mode || '').toLowerCase() === 'hotspot';
  hint.textContent = hotspotMode
    ? 'Known networks are saved. Switch to client mode to join one.'
    : 'Networks saved in NetworkManager.';

  container.innerHTML = '';
  networks.forEach((network) => {
    const row = document.createElement('div');
    row.className = 'wifi-network-item';

    const left = document.createElement('div');
    const ssid = network.ssid || '(unnamed)';
    const isActive = ssid === activeWifiConnection;
    const auto = network.autoconnect ? 'Auto-connect' : 'Manual connect';

    const title = document.createElement('strong');
    title.textContent = ssid;
    const meta = document.createElement('div');
    meta.className = 'wifi-network-meta';
    meta.textContent = isActive ? `${auto} - connected` : auto;
    left.appendChild(title);
    left.appendChild(meta);

    const button = document.createElement('button');
    button.className = 'btn btn-primary btn-small';
    button.type = 'button';
    button.textContent = isActive ? 'Connected' : (hotspotMode ? 'Switch & Join' : 'Join');
    button.disabled = isActive;
    button.onclick = () => connectWifi(ssid, '');

    row.appendChild(left);
    row.appendChild(button);
    container.appendChild(row);
  });
}

function renderWifiNetworks(networks) {
  const container = document.getElementById('wifiScanResults');
  if (!container) return;
  if (!Array.isArray(networks) || networks.length === 0) {
    container.innerHTML = '<div class="wifi-network-meta">No WiFi networks found.</div>';
    return;
  }
  container.innerHTML = '';
  networks.forEach((network) => {
    const row = document.createElement('div');
    row.className = 'wifi-network-item';
    const left = document.createElement('div');
    const ssid = network.ssid || '(hidden)';
    const sec = network.security || 'open';
    left.innerHTML = `<strong>${ssid}</strong><div class="wifi-network-meta">Signal ${network.signal || 0}% - ${sec}</div>`;
    const button = document.createElement('button');
    button.className = 'btn btn-primary btn-small';
    button.type = 'button';
    button.textContent = 'Use';
    button.onclick = () => {
      const input = document.getElementById('wifiSsid');
      if (input) input.value = ssid;
    };
    row.appendChild(left);
    row.appendChild(button);
    container.appendChild(row);
  });
}

async function loadWifiStatus() {
  try {
    const res = await fetch('/api/wifi');
    const data = await readJsonOrError(res);
    if (!res.ok) {
      throw new Error(data.error || res.statusText);
    }
    renderWifiMode(data.mode);
    renderWifiStatusContext(data);
    document.getElementById('wifiHotspotSsid').textContent = data.hotspot?.ssid || '—';
    document.getElementById('wifiHotspotPassword').textContent = data.hotspot?.password || '—';
    renderWifiQr(data.hotspot?.ssid, data.hotspot?.password);
    renderKnownNetworks(data.knownClientNetworks || [], data.activeWifiConnection || data.wifiConnection || '', data.mode || '');
  } catch (err) {
    console.error('loadWifiStatus:', err);
  }
}

async function scanWifi() {
  try {
    const res = await fetch('/api/wifi/scan');
    const data = await readJsonOrError(res);
    if (!res.ok) {
      throw new Error(data.error || res.statusText);
    }
    renderWifiNetworks(data.networks || []);
  } catch (err) {
    showStatus('WiFi scan failed: ' + err.message, 'error');
  }
}

async function connectWifi(ssidOverride, passwordOverride) {
  const ssid = typeof ssidOverride === 'string'
    ? ssidOverride.trim()
    : (document.getElementById('wifiSsid')?.value || '').trim();
  const password = typeof passwordOverride === 'string'
    ? passwordOverride
    : (document.getElementById('wifiPassword')?.value || '');
  if (!ssid) {
    showStatus('Enter an SSID to connect.', 'error');
    return;
  }
  try {
    const params = new URLSearchParams();
    params.set('ssid', ssid);
    if (password) {
      params.set('password', password);
    }
    const res = await fetch('/api/wifi/connect?' + params.toString(), { method: 'POST' });
    const data = await readJsonOrError(res);
    if (!res.ok) {
      throw new Error(data.error || res.statusText);
    }
    showStatus('Connected to WiFi network: ' + (data.connectedSsid || ssid), 'success');
    loadWifiStatus();
    scanWifi();
  } catch (err) {
    showStatus('WiFi connect failed: ' + err.message, 'error');
  }
}

async function setWifiMode(mode) {
  try {
    const params = new URLSearchParams();
    params.set('mode', mode);
    const res = await fetch('/api/wifi/mode?' + params.toString(), { method: 'POST' });
    const data = await readJsonOrError(res);
    if (!res.ok) {
      throw new Error(data.error || res.statusText);
    }
    showStatus('WiFi mode set to ' + (data.mode || mode), 'success');
    loadWifiStatus();
  } catch (err) {
    showStatus('Failed to switch WiFi mode: ' + err.message, 'error');
  }
}

async function applyWifiFallback() {
  try {
    const res = await fetch('/api/wifi/fallback', { method: 'POST' });
    const data = await readJsonOrError(res);
    if (!res.ok) {
      throw new Error(data.error || res.statusText);
    }
    showStatus('Fallback action: ' + (data.action || data.status || 'done'), 'success');
    loadWifiStatus();
  } catch (err) {
    showStatus('Failed to apply fallback: ' + err.message, 'error');
  }
}

document.getElementById('btnWifiModeClient')?.addEventListener('click', () => setWifiMode('client'));
document.getElementById('btnWifiModeHotspot')?.addEventListener('click', () => setWifiMode('hotspot'));
document.getElementById('btnWifiApplyFallback')?.addEventListener('click', applyWifiFallback);
document.getElementById('btnWifiScan')?.addEventListener('click', scanWifi);
document.getElementById('btnWifiConnect')?.addEventListener('click', connectWifi);

// --- Sensors / Nodes page (View / Add / Configure) ---
let sensorFlashPollTimer = null;
let sensorsPageCache = null;
let nodesUi = {
  screen: 'view',
  addMethod: null,
  addStep: 'method',
  /** Draft fields for Add wizard (survives poll remounts). */
  addDraft: {},
  configureId: null,
  pickType: null,
  selectedPins: [],
  editingOutputIx: null,
  editOriginalDcc: null,
  /** Per-output last press for highlight: "boardId#outIx" → "dcc:on|off". */
  lastAccessoryByOutput: {},
  _configureStructureKey: null,
  /** Assigned-function pin finder: output index to ring on the board diagram. */
  highlightOutIx: null,
  /** Optical district editor: index in modal, or null when creating new. */
  editingDistrictIx: null,
  /** Draft district while modal open (mutated by pin pick / calibrate). */
  _modalDistrict: null,
  /** Pick mode for districts: 'ref' | 'sensors' while pickType === 'optical_district'. */
  pickDistrictMode: null,
  /** true = left-pane create flow; false = re-pick from open modal. */
  districtCreating: false,
  /** Magnifier highlight for a district index (like highlightOutIx). */
  highlightDistIx: null,
  /** Live calibrate state for a district. */
  calibrate: null
};

let nodeModalTriggers = [];

/** Fallback pin slots when schema does not include pinSlots. */
const OUTPUT_PIN_SLOTS = {
  level_crossing: [
    { key: 'warning', label: 'Warning light', caps: ['digital_out', 'pwm'] },
    { key: 'wigwag_left', label: 'Left wig-wag', caps: ['digital_out'] },
    { key: 'wigwag_right', label: 'Right wig-wag', caps: ['digital_out'] },
    { key: 'barrier1', label: 'Barrier 1', caps: ['servo'] },
    { key: 'barrier2', label: 'Barrier 2', caps: ['servo'] }
  ]
};

/** Free-form output names for a multi-out signal (parallel to pins[]). */
function signalPinNames(pins, existing) {
  const n = (pins || []).length;
  const names = Array.isArray(existing) ? existing.map((s) => String(s || '')) : [];
  const out = [];
  for (let i = 0; i < n; i++) {
    out.push((names[i] || '').trim() || ('Output ' + (i + 1)));
  }
  return out;
}

function multiOutDefaultTrigger(baseDcc, num) {
  return {
    state: 'config_' + (num || 1),
    label: 'Config #' + (num || 1),
    on: [],
    flashing: [],
    dcc: Number(baseDcc) || 1,
    direction: 'Forward'
  };
}

function pinLevelForConfig(trig, pinIx) {
  const flash = Array.isArray(trig?.flashing) ? trig.flashing.map(Number) : [];
  const on = Array.isArray(trig?.on) ? trig.on.map(Number) : [];
  if (flash.includes(pinIx)) return 'flashing';
  if (on.includes(pinIx)) return 'on';
  // Legacy lamps[] + boolean flashing
  if (Array.isArray(trig?.lamps) && trig.lamps.map(Number).includes(pinIx)) {
    return (trig.flashing === true || trig.flashing === 'true') ? 'flashing' : 'on';
  }
  return 'off';
}

function applyPinLevelToTrigger(trig, pinIx, level) {
  const on = new Set((trig.on || []).map(Number));
  const flash = new Set((trig.flashing || []).map(Number));
  on.delete(pinIx);
  flash.delete(pinIx);
  if (level === 'on') on.add(pinIx);
  else if (level === 'flashing') {
    on.add(pinIx);
    flash.add(pinIx);
  }
  trig.on = [...on].sort((a, b) => a - b);
  trig.flashing = [...flash].sort((a, b) => a - b);
  delete trig.lamps;
  delete trig.route;
  delete trig.aspect;
}

function pinSlotsForType(typeId, params) {
  const meta = outputTypeMeta(typeId);
  if (meta?.pinSlots?.length) return meta.pinSlots;
  return OUTPUT_PIN_SLOTS[typeId] || null;
}

function nodesHash(screen, id) {
  if (screen === 'add') return '#sensors/add';
  if (screen === 'audit') return '#sensors/audit';
  if (screen === 'configure' && id) return '#sensors/configure/' + encodeURIComponent(id);
  return '#sensors';
}

function syncNodesScreenFromHash() {
  const { parts } = parseLocationHash();
  if (parts[0] !== 'sensors') return;
  if (parts[1] === 'add') {
    showNodesScreen('add');
  } else if (parts[1] === 'audit') {
    showNodesScreen('audit');
  } else if (parts[1] === 'configure' && parts[2]) {
    nodesUi.configureId = decodeURIComponent(parts[2]);
    showNodesScreen('configure');
  } else {
    showNodesScreen('view');
  }
}

function showNodesScreen(screen) {
  nodesUi.screen = screen;
  document.querySelectorAll('.nodes-screen').forEach((el) => {
    const match = el.getAttribute('data-nodes-screen') === screen;
    el.hidden = !match;
    el.classList.toggle('is-active', match);
  });
  const title = document.getElementById('nodesScreenTitle');
  const hint = document.getElementById('nodesScreenHint');
  const actions = document.getElementById('nodesScreenActions');
  if (actions) actions.innerHTML = '';

  if (screen === 'view') {
    if (title) title.textContent = 'Nodes';
    if (hint) hint.textContent = 'Live inputs and configured outputs. Open Configure for the pin map.';
    if (actions) {
      const auditBtn = document.createElement('button');
      auditBtn.type = 'button';
      auditBtn.className = 'btn btn-secondary';
      auditBtn.textContent = 'DCC audit';
      auditBtn.onclick = () => setPageHash(nodesHash('audit'));
      actions.appendChild(auditBtn);
      const addBtn = document.createElement('button');
      addBtn.type = 'button';
      addBtn.className = 'btn btn-primary';
      addBtn.textContent = 'Add node';
      addBtn.onclick = () => {
        nodesUi.addMethod = null;
        nodesUi.addStep = 'method';
        setPageHash(nodesHash('add'));
      };
      actions.appendChild(addBtn);
    }
    renderNodesView();
  } else if (screen === 'add') {
    if (title) title.textContent = 'Add node';
    if (hint) hint.textContent = 'A short workflow — ends on Configure so you can wire pins.';
    if (actions) {
      const back = document.createElement('button');
      back.type = 'button';
      back.className = 'btn btn-secondary';
      back.textContent = 'Back to list';
      back.onclick = () => setPageHash(nodesHash('view'));
      actions.appendChild(back);
    }
    renderNodesAdd();
  } else if (screen === 'audit') {
    if (title) title.textContent = 'DCC audit';
    if (hint) {
      hint.hidden = false;
      hint.textContent = 'Every accessory address used by node outputs — shared addresses and direction clashes at a glance.';
    }
    if (actions) {
      const back = document.createElement('button');
      back.type = 'button';
      back.className = 'btn btn-secondary';
      back.textContent = 'Back to list';
      back.onclick = () => setPageHash(nodesHash('view'));
      actions.appendChild(back);
    }
    renderNodesAudit();
  } else if (screen === 'configure') {
    const board = findBoardById(nodesUi.configureId);
    if (title) title.textContent = board ? ('Configure · Prefix ' + (board.prefix || '?')) : 'Configure';
    if (hint) {
      hint.textContent = '';
      hint.hidden = true;
    }
    if (actions) {
      const back = document.createElement('button');
      back.type = 'button';
      back.className = 'btn btn-secondary';
      back.textContent = 'Back to list';
      back.onclick = () => setPageHash(nodesHash('view'));
      actions.appendChild(back);
    }
    renderNodesConfigure();
  } else {
    nodesUi.highlightOutIx = null;
  }
  if ((screen === 'view' || screen === 'audit') && hint) hint.hidden = false;
}

function findBoardById(id) {
  return (sensorsPageCache?.boards || []).find((b) => b.id === id) || null;
}

function findProfile(boardType) {
  return (sensorsPageCache?.boardProfiles || []).find((p) => p.id === boardType) || null;
}

function outputTypeMeta(typeId) {
  return (sensorsPageCache?.outputTypes || []).find((t) => t.id === typeId) || null;
}

function pinsNeededForType(typeId, params) {
  if (typeId === 'digital_signal') return 99; // open-ended; user presses Done
  const meta = outputTypeMeta(typeId);
  const slots = pinSlotsForType(typeId, params);
  if (slots?.length) return slots.length;
  if (!meta) return 1;
  if (meta.pinCount > 0 && !meta.variablePinSlots) return meta.pinCount;
  return 1;
}

function pinSlotLabels(typeId, params, pinCount) {
  const slots = pinSlotsForType(typeId, params);
  if (slots?.length) return slots.map((s) => s.label);
  if (typeId === 'digital_signal') {
    const n = pinCount != null ? pinCount : Math.max(1, nodesUi.selectedPins.length);
    return Array.from({ length: n }, (_, i) => 'Output ' + (i + 1));
  }
  const n = pinsNeededForType(typeId, params);
  const name = outputLabel(typeId);
  if (n === 1) return [name];
  return Array.from({ length: n }, (_, i) => name + ' pin ' + (i + 1));
}

function slotCapsForPick(typeId, pinIx) {
  const slots = pinSlotsForType(typeId);
  if (slots && slots[pinIx] && slots[pinIx].caps) {
    return slots[pinIx].caps;
  }
  const meta = outputTypeMeta(typeId);
  return meta?.requiredCapabilities || ['digital_out'];
}

function pinIsReserved(pin) {
  const caps = pin.capabilities || [];
  // Ambient "ref" capability on profiles is not locked — districts assign Reference explicitly.
  if (caps.includes('uart')) return true;
  if (caps.includes('led') && !caps.includes('digital_out')) return true;
  return false;
}

function pinSelectableForPick(pin, usage, typeId) {
  const label = pin.silkLabel;
  if (nodesUi.selectedPins.includes(label)) return true;
  if (pinIsReserved(pin)) return false;
  if (usage && usage.role === 'output') return false;
  if (typeId === 'optical_district') {
    const caps = pin.capabilities || [];
    if (!caps.includes('optical')) return false;
    // Ref pick: any free analogue (not another district's sensor/ref unless this district)
    const mode = nodesUi.pickDistrictMode || 'sensors';
    const dix = nodesUi.editingDistrictIx;
    if (usage?.role === 'ref' && usage.districtIx !== dix) return false;
    if (usage?.role === 'optical' && usage.districtIx != null && usage.districtIx !== dix) return false;
    if (mode === 'ref') return true;
    // sensors: cannot pick the district's own reference (saved or draft)
    const draftRef = nodesUi._modalDistrict?.ref;
    if (draftRef && label === draftRef) return false;
    if (usage?.role === 'ref') return false;
    return true;
  }
  const needCaps = slotCapsForPick(typeId, nodesUi.selectedPins.length);
  const caps = pin.capabilities || [];
  return needCaps.some((c) => caps.includes(c));
}

function boardOpticalDistricts(board) {
  if (Array.isArray(board?.opticalDistricts) && board.opticalDistricts.length) {
    return board.opticalDistricts.map((d) => normalizeDistrict(d));
  }
  if (Array.isArray(board?.config?.opticalDistricts) && board.config.opticalDistricts.length) {
    return board.config.opticalDistricts.map((d) => normalizeDistrict(d));
  }
  return [];
}

function normalizeDistrict(d) {
  return {
    name: (d?.name != null ? String(d.name) : '').trim() || 'District',
    ref: String(d?.ref || '').toUpperCase(),
    sensors: Array.isArray(d?.sensors)
      ? d.sensors.map((s) => String(s).toUpperCase()).filter(Boolean)
      : [],
    sensitivityPct: clampSensitivityPct(d?.sensitivityPct)
  };
}

function clampSensitivityPct(v) {
  const n = Number(v);
  if (!Number.isFinite(n)) return 15;
  return Math.max(1, Math.min(100, Math.round(n)));
}

function suggestDistrictName(board) {
  const used = new Set(boardOpticalDistricts(board).map((d) => d.name.toLowerCase()));
  if (!used.has('district')) return 'District';
  let i = 2;
  while (used.has('district ' + i)) i++;
  return 'District ' + i;
}

function pickInstructionText(typeId) {
  if (typeId === 'optical_district') {
    if (nodesUi.pickDistrictMode === 'ref') {
      let text = 'Select a <strong>Reference</strong> pin on the board (ambient light).';
      if (nodesUi.selectedPins.length) {
        text += '<br><span class="nodes-coach-picked">Selected: ' + nodesUi.selectedPins.join(', ') + '</span>';
      }
      return text;
    }
    let text = 'Select <strong>sensor</strong> pins for this district, then press <strong>Done</strong>.';
    if (nodesUi.selectedPins.length) {
      text += '<br><span class="nodes-coach-picked">Selected: ' + nodesUi.selectedPins.join(', ') + '</span>';
    }
    return text;
  }
  if (typeId === 'digital_signal') {
    let text = 'Select digital output pins on the board, then press <strong>Done</strong>.';
    if (nodesUi.selectedPins.length) {
      text += '<br><span class="nodes-coach-picked">Selected: ' + nodesUi.selectedPins.join(', ') + '</span>';
    }
    return text;
  }
  const need = pinsNeededForType(typeId);
  const slots = pinSlotLabels(typeId);
  const next = slots[nodesUi.selectedPins.length] || 'pin';
  const step = nodesUi.selectedPins.length + 1;
  let text = 'Select an available pin on the board for <strong>' + next + '</strong>';
  if (need > 1 && need < 90) {
    text += ' <span class="nodes-coach-step">(' + step + ' of ' + need + ')</span>';
  }
  text += '.';
  if (nodesUi.selectedPins.length) {
    text += '<br><span class="nodes-coach-picked">Selected: ' + nodesUi.selectedPins.join(', ') + '</span>';
  }
  return text;
}

function startSensorDistrictPick() {
  closeDistrictModal();
  nodesUi.pickType = 'optical_district';
  nodesUi.pickDistrictMode = 'ref';
  nodesUi.districtCreating = true;
  nodesUi.editingDistrictIx = null;
  nodesUi.editingOutputIx = null;
  nodesUi._modalDistrict = null;
  nodesUi.selectedPins = [];
  nodesUi._configureStructureKey = null;
  renderNodesConfigure();
}

function renderTypePicker() {
  const host = document.getElementById('nodesTypePicker');
  if (!host) return;
  host.innerHTML = '';
  if (!nodesUi.pickType) {
    const list = document.createElement('div');
    list.className = 'nodes-type-list';
    const outLabel = document.createElement('div');
    outLabel.className = 'nodes-type-group-label';
    outLabel.textContent = 'Outputs';
    list.appendChild(outLabel);
    (sensorsPageCache?.outputTypes || []).forEach((t) => {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'nodes-type-item';
      btn.textContent = t.label || t.id;
      btn.onclick = () => {
        nodesUi.pickType = t.id;
        nodesUi.selectedPins = [];
        nodesUi.editingOutputIx = null;
        nodesUi.districtCreating = false;
        renderNodesConfigure();
      };
      list.appendChild(btn);
    });
    const inLabel = document.createElement('div');
    inLabel.className = 'nodes-type-group-label';
    inLabel.textContent = 'Inputs';
    list.appendChild(inLabel);
    const distBtn = document.createElement('button');
    distBtn.type = 'button';
    distBtn.className = 'nodes-type-item';
    distBtn.textContent = 'Sensor district';
    distBtn.onclick = () => startSensorDistrictPick();
    list.appendChild(distBtn);
    host.appendChild(list);
    return;
  }
  const selected = document.createElement('div');
  selected.className = 'nodes-type-selected';
  const pickTitle = nodesUi.pickType === 'optical_district'
    ? (nodesUi.pickDistrictMode === 'ref' ? 'Sensor district · Reference' : 'Sensor district · Sensors')
    : outputLabel(nodesUi.pickType);
  selected.innerHTML =
    '<div class="nodes-type-selected-name">' + pickTitle + '</div>' +
    '<button type="button" class="btn btn-secondary btn-small" id="btnNodesClearPick">' +
    (nodesUi.pickType === 'optical_district' ? 'Cancel' : 'Change') + '</button>';
  host.appendChild(selected);
  document.getElementById('btnNodesClearPick')?.addEventListener('click', () => {
    if (nodesUi.pickType === 'optical_district') {
      const wasCreating = nodesUi.districtCreating;
      const draft = nodesUi._modalDistrict;
      nodesUi.pickType = null;
      nodesUi.pickDistrictMode = null;
      nodesUi.selectedPins = [];
      nodesUi.districtCreating = false;
      nodesUi._configureStructureKey = null;
      renderNodesConfigure();
      if (!wasCreating && draft && nodesUi.editingDistrictIx != null) {
        syncDistrictModalSummary();
        document.getElementById('nodesDistrictModal').hidden = false;
      } else {
        nodesUi._modalDistrict = null;
        nodesUi.editingDistrictIx = null;
      }
      return;
    }
    nodesUi.pickType = null;
    nodesUi.selectedPins = [];
    renderNodesConfigure();
  });
  const coach = document.createElement('div');
  coach.className = 'nodes-coach';
  coach.innerHTML = pickInstructionText(nodesUi.pickType);
  host.appendChild(coach);
  if (nodesUi.pickType === 'digital_signal') {
    const actions = document.createElement('div');
    actions.className = 'nodes-signal-shape-actions';
    const done = document.createElement('button');
    done.type = 'button';
    done.className = 'btn btn-primary btn-small';
    done.id = 'btnNodesSignalDone';
    done.textContent = 'Done';
    done.disabled = nodesUi.selectedPins.length < 1;
    done.onclick = () => {
      const board = findBoardById(nodesUi.configureId);
      if (!board || nodesUi.selectedPins.length < 1) return;
      openNewOutputModal(board, 'digital_signal', nodesUi.selectedPins.slice());
    };
    actions.appendChild(done);
    host.appendChild(actions);
  }
  if (nodesUi.pickType === 'optical_district' && nodesUi.pickDistrictMode === 'sensors') {
    const actions = document.createElement('div');
    actions.className = 'nodes-signal-shape-actions';
    const done = document.createElement('button');
    done.type = 'button';
    done.className = 'btn btn-primary btn-small';
    done.textContent = 'Done';
    done.disabled = nodesUi.selectedPins.length < 1;
    done.onclick = () => finishDistrictSensorPick();
    actions.appendChild(done);
    host.appendChild(actions);
  }
}

function fillBoardTypeSelects(profiles) {
  ['sensorFlashBoardType', 'shimBoardType'].forEach((id) => {
    const sel = document.getElementById(id);
    if (!sel) return;
    const prev = sel.value;
    sel.innerHTML = '';
    (profiles || []).forEach((p) => {
      const opt = document.createElement('option');
      opt.value = p.id;
      opt.textContent = p.label || p.id;
      sel.appendChild(opt);
    });
    if (prev) sel.value = prev;
  });
}

function setOutputTypeDisplay(typeId) {
  const hidden = document.getElementById('nodeOutputType');
  const label = document.getElementById('nodeOutputTypeLabel');
  if (hidden) hidden.value = typeId || '';
  if (label) label.textContent = outputLabel(typeId) || typeId || '—';
}

function directionToWire(direction) {
  const d = String(direction || 'Forward').toLowerCase();
  if (d.startsWith('rev') || d === 'thrown' || d === 'off') return 'OFF';
  return 'ON';
}

function normalizeOutputTriggers(output, typeMeta) {
  if (Array.isArray(output?.triggers) && output.triggers.length) {
    return output.triggers.map((t) => Object.assign({}, t));
  }
  // Legacy: single dcc + posA/posB / brightnessOn/Off
  const dcc = Number(output?.dcc) || 1;
  const params = output?.params || {};
  if ((typeMeta?.triggerMode === 'positions' || output?.type === 'servo') &&
      (params.posA_deg != null || params.posB_deg != null)) {
    return [
      { label: 'Position 1', dcc, direction: 'Forward', deg: Number(params.posA_deg) || 0 },
      { label: 'Position 2', dcc, direction: 'Reverse', deg: Number(params.posB_deg) || 90 }
    ];
  }
  if ((output?.type === 'fade' || typeMeta?.id === 'fade') &&
      (params.brightnessOn != null || params.brightnessOff != null)) {
    return [
      { label: 'Level 1', dcc, direction: 'Forward', brightness: Number(params.brightnessOn) || 100 },
      { label: 'Level 2', dcc, direction: 'Reverse', brightness: Number(params.brightnessOff) || 0 }
    ];
  }
  const defaults = (typeMeta?.defaultTriggers || []).map((t) => Object.assign({}, t));
  if (!defaults.length) {
    return [{ dcc, direction: 'Forward' }];
  }
  return defaults.map((t, i) => {
    const copy = Object.assign({}, t);
    if (i === 0) copy.dcc = dcc;
    return copy;
  });
}

function positionsValueField(typeMeta) {
  return (typeMeta?.triggerFields || []).find((f) => f.testable) ||
    (typeMeta?.triggerFields || []).find((f) => f.id === 'deg' || f.id === 'brightness') ||
    null;
}

function triggerSummary(output) {
  const triggers = Array.isArray(output?.triggers) ? output.triggers : [];
  if (!triggers.length && output?.dcc != null) {
    return 'DCC ' + output.dcc;
  }
  return triggers.map((t) => {
    const dir = (t.direction || '?').charAt(0);
    let suffix = '';
    if (t.deg != null) suffix = ' @' + t.deg + '°';
    else if (t.brightness != null) suffix = ' @' + t.brightness + '%';
    return t.dcc + dir + suffix;
  }).join(', ');
}

function renderSignalPinNameFields(host, pins, existingNames) {
  const names = signalPinNames(pins, existingNames);
  const box = document.createElement('div');
  box.className = 'nodes-signal-pin-names';
  const title = document.createElement('span');
  title.className = 'nodes-signal-pin-names-label';
  title.textContent = 'Output names';
  box.appendChild(title);
  const row = document.createElement('div');
  row.className = 'nodes-signal-pin-names-row';
  pins.forEach((pin, i) => {
    const wrap = document.createElement('label');
    wrap.className = 'nodes-signal-pin-name';
    wrap.innerHTML = '<span>' + escapeHtml(pin) + '</span>';
    const input = document.createElement('input');
    input.type = 'text';
    input.dataset.pinName = String(i);
    input.value = names[i] || '';
    input.placeholder = 'Output ' + (i + 1);
    input.addEventListener('change', () => {
      if (document.getElementById('nodeOutputType')?.value === 'digital_signal') {
        renderNodeTriggersEditor();
      }
    });
    wrap.appendChild(input);
    row.appendChild(wrap);
  });
  box.appendChild(row);
  host.appendChild(box);
}

function collectSignalPinNames() {
  const host = document.getElementById('nodeOutputParams');
  const pins = nodesUi._modalPins || [];
  if (!host) return signalPinNames(pins, []);
  const names = [];
  pins.forEach((_, i) => {
    const input = host.querySelector('[data-pin-name="' + i + '"]');
    names.push((input?.value || '').trim() || ('Output ' + (i + 1)));
  });
  return names;
}

function usedVirtualLabelsOnBoard(board, excludeOutputIx) {
  const used = new Set();
  (board?.outputs || []).forEach((o, ix) => {
    if (excludeOutputIx != null && ix === excludeOutputIx) return;
    if (o.type !== 'level_crossing') return;
    const v = String(o.params?.feedbackVirtual || '').trim().toUpperCase();
    const m = v.match(/^V?(\d+)$/) || v.match(/^[A-Z]{1,2}-V(\d+)$/);
    if (m) {
      const n = Number(m[1]);
      if (n >= 1 && n <= 12) used.add('V' + n);
    }
  });
  return used;
}

function nextFreeVirtualLabel(board, excludeOutputIx) {
  const used = usedVirtualLabelsOnBoard(board, excludeOutputIx);
  for (let n = 1; n <= 12; n++) {
    if (!used.has('V' + n)) return 'V' + n;
  }
  return null;
}

function normalizeVirtualLabelUi(raw) {
  const s = String(raw || '').trim().toUpperCase();
  if (!s) return '';
  let m = s.match(/^([A-Z]{1,2}-)?V(\d+)$/);
  if (m) {
    const n = Number(m[2]);
    return (n >= 1 && n <= 12) ? ('V' + n) : '';
  }
  m = s.match(/^(\d+)$/);
  if (m) {
    const n = Number(m[1]);
    return (n >= 1 && n <= 12) ? ('V' + n) : '';
  }
  return '';
}

function renderLevelCrossingFeedbackField(host, defaults) {
  const board = findBoardById(nodesUi._modalBoardId || nodesUi.configureId);
  const prefix = board?.prefix || '?';
  const used = usedVirtualLabelsOnBoard(board, nodesUi.editingOutputIx);
  let current = normalizeVirtualLabelUi(defaults.feedbackVirtual);
  if (!current) current = nextFreeVirtualLabel(board, nodesUi.editingOutputIx) || 'V1';
  const wrap = document.createElement('div');
  wrap.className = 'nodes-param nodes-lc-feedback';
  wrap.innerHTML = '<span>Virtual feedback</span>';
  const control = document.createElement('div');
  control.className = 'nodes-param-control';
  const sel = document.createElement('select');
  sel.dataset.param = 'feedbackVirtual';
  for (let n = 1; n <= 12; n++) {
    const label = 'V' + n;
    const opt = document.createElement('option');
    opt.value = label;
    const taken = used.has(label) && label !== current;
    opt.textContent = label + ' → ' + prefix + '-' + label + (taken ? ' (in use)' : '');
    opt.disabled = taken;
    if (label === current) opt.selected = true;
    sel.appendChild(opt);
  }
  control.appendChild(sel);
  wrap.appendChild(control);
  const hint = document.createElement('p');
  hint.className = 'wifi-hint nodes-lc-feedback-hint';
  hint.textContent = 'Goes high when Closed completes, low when Open completes. Use ' +
    prefix + '-' + current + ' as accessory feedbackHardwareId.';
  sel.addEventListener('change', () => {
    hint.textContent = 'Goes high when Closed completes, low when Open completes. Use ' +
      prefix + '-' + sel.value + ' as accessory feedbackHardwareId.';
  });
  wrap.appendChild(hint);
  // Keep active-state default without exposing a control
  const hidden = document.createElement('input');
  hidden.type = 'hidden';
  hidden.dataset.param = 'feedbackActiveState';
  hidden.value = defaults.feedbackActiveState != null ? String(defaults.feedbackActiveState) : '1';
  wrap.appendChild(hidden);
  host.appendChild(wrap);
}

function renderNodeOutputParams(existingParams) {
  const host = document.getElementById('nodeOutputParams');
  const section = document.getElementById('nodeParamsSection');
  const type = document.getElementById('nodeOutputType')?.value || 'servo';
  const meta = outputTypeMeta(type);
  if (!host) return;
  const fields = meta?.paramFields || [];
  const defaults = Object.assign({}, meta?.defaultParams || {}, existingParams || {});
  if (!fields.length && type !== 'digital_signal') {
    host.innerHTML = '';
    if (section) section.hidden = true;
    return;
  }
  if (section) section.hidden = false;
  host.innerHTML = '';
  if (type === 'digital_signal') {
    const pins = nodesUi._modalPins || [];
    const existingNames = defaults.pinNames || nodesUi._modalPinNames || [];
    renderSignalPinNameFields(host, pins, existingNames);
  }
  if (type === 'level_crossing') {
    renderLevelCrossingFeedbackField(host, defaults);
  }
  fields.forEach((f) => {
    if (type === 'level_crossing' && (f.id === 'feedbackVirtual' || f.id === 'feedbackActiveState')) {
      return;
    }
    const val = defaults[f.id] != null ? defaults[f.id] : (f.default != null ? f.default : '');
    const wrap = document.createElement('label');
    wrap.className = 'nodes-param' + (f.testable ? ' has-action' : '');
    const title = document.createElement('span');
    title.textContent = f.title || f.id;
    wrap.appendChild(title);
    const control = document.createElement('div');
    control.className = 'nodes-param-control';
    const input = document.createElement('input');
    input.dataset.param = f.id;
    input.type = f.type === 'integer' ? 'number' : 'text';
    if (f.type === 'integer') {
      input.min = '0';
      if (String(f.id).endsWith('_deg')) {
        input.max = '180';
      }
    }
    input.value = val;
    control.appendChild(input);
    if (f.testable) {
      const testBtn = document.createElement('button');
      testBtn.type = 'button';
      testBtn.className = 'btn btn-secondary btn-small';
      testBtn.textContent = 'Test angle';
      testBtn.onclick = (ev) => {
        ev.preventDefault();
        testOutputParamDeg(f.id);
      };
      control.appendChild(testBtn);
    }
    wrap.appendChild(control);
    host.appendChild(wrap);
  });
}

function collectNodeOutputParams() {
  const host = document.getElementById('nodeOutputParams');
  const params = {};
  if (!host) return params;
  host.querySelectorAll('[data-param]').forEach((input) => {
    const key = input.getAttribute('data-param');
    const raw = input.value;
    if (input.dataset.json === '1') {
      try {
        params[key] = JSON.parse(raw);
      } catch (_) {
        params[key] = raw;
      }
      return;
    }
    const n = Number(raw);
    params[key] = Number.isFinite(n) && String(n) === String(raw).trim() ? n : raw;
  });
  return params;
}

function renderNodeTriggersEditor() {
  const host = document.getElementById('nodeOutputTriggers');
  const addBtn = document.getElementById('btnNodeAddTrigger');
  const type = document.getElementById('nodeOutputType')?.value || 'servo';
  const meta = outputTypeMeta(type);
  if (!host || !meta) return;
  const mode = meta.triggerMode || 'fixed_states';
  const editableRows = mode === 'positions' || mode === 'patterns';
  if (addBtn) {
    addBtn.hidden = !editableRows;
    addBtn.disabled = nodeModalTriggers.length >= (meta.maxTriggers || 16);
    if (type === 'fade') addBtn.textContent = 'Add level';
    else if (type === 'digital_signal') addBtn.textContent = 'Add config';
    else addBtn.textContent = 'Add position';
  }
  const fields = meta.triggerFields || [];
  const pinNames = type === 'digital_signal'
    ? collectSignalPinNames()
    : [];
  host.innerHTML = '';
  nodeModalTriggers.forEach((trig, ix) => {
    if (type === 'digital_signal') {
      trig.state = 'config_' + (ix + 1);
      if (!trig.label || /^config\s*#?\d+/i.test(String(trig.label))) {
        trig.label = 'Config #' + (ix + 1);
      }
      if (!Array.isArray(trig.on)) trig.on = [];
      if (!Array.isArray(trig.flashing) || typeof trig.flashing === 'string') {
        // migrate legacy
        const legacyFlash = trig.flashing === true || trig.flashing === 'true';
        const lamps = Array.isArray(trig.lamps) ? trig.lamps.map(Number) : trig.on.map(Number);
        trig.on = lamps.slice();
        trig.flashing = legacyFlash ? lamps.slice() : [];
      }
    }
    const row = document.createElement('div');
    row.className = 'nodes-trigger-row';
    const head = document.createElement('div');
    head.className = 'nodes-trigger-row-head';
    const stateLabel = trig.label || trig.state || ('Config #' + (ix + 1));
    head.innerHTML = '<strong>' + escapeHtml(String(stateLabel)) + '</strong>';
    if (editableRows) {
      const rem = document.createElement('button');
      rem.type = 'button';
      rem.className = 'btn btn-secondary btn-small';
      rem.textContent = 'Remove';
      rem.disabled = nodeModalTriggers.length <= (meta.minTriggers || 1);
      rem.onclick = () => {
        nodeModalTriggers.splice(ix, 1);
        renderNodeTriggersEditor();
      };
      head.appendChild(rem);
    }
    row.appendChild(head);
    const grid = document.createElement('div');
    grid.className = 'nodes-trigger-fields';

    if (type === 'digital_signal') {
      const outWrap = document.createElement('div');
      outWrap.className = 'nodes-trigger-field nodes-signal-lamps';
      outWrap.innerHTML = '<span>Outputs</span>';
      const outBox = document.createElement('div');
      outBox.className = 'nodes-signal-out-levels';
      pinNames.forEach((name, pi) => {
        const lab = document.createElement('label');
        lab.className = 'nodes-param';
        lab.innerHTML = '<span>' + escapeHtml(name) + '</span>';
        const sel = document.createElement('select');
        [['off', 'Off'], ['on', 'On'], ['flashing', 'Flashing']].forEach(([v, t]) => {
          const opt = document.createElement('option');
          opt.value = v;
          opt.textContent = t;
          sel.appendChild(opt);
        });
        sel.value = pinLevelForConfig(trig, pi);
        sel.addEventListener('change', () => {
          applyPinLevelToTrigger(nodeModalTriggers[ix], pi, sel.value);
        });
        lab.appendChild(sel);
        outBox.appendChild(lab);
      });
      outWrap.appendChild(outBox);
      grid.appendChild(outWrap);
    }

    fields.forEach((f) => {
      if (!editableRows && f.id === 'label') return;
      if (mode === 'patterns' && f.id === 'label' && type === 'digital_signal') {
        // editable config name
      } else if (mode === 'patterns' && f.id === 'label') {
        return;
      }
      if (f.id === 'aspect' || f.id === 'route' || (f.id === 'flashing' && type === 'digital_signal')) return;
      const wrap = document.createElement('div');
      wrap.className = 'nodes-trigger-field' + (f.testable ? ' has-action' : '');
      const lab = document.createElement('span');
      lab.textContent = f.id === 'label' && type === 'digital_signal' ? 'Config name' : (f.title || f.id);
      wrap.appendChild(lab);
      const control = document.createElement('div');
      control.className = 'nodes-trigger-control';
      let input;
      if (f.type === 'enum' && Array.isArray(f.enum)) {
        input = document.createElement('select');
        f.enum.forEach((v) => {
          const opt = document.createElement('option');
          opt.value = v;
          opt.textContent = v;
          input.appendChild(opt);
        });
        input.value = trig[f.id] || f.enum[0];
      } else {
        input = document.createElement('input');
        input.type = f.type === 'integer' ? 'number' : 'text';
        if (f.minimum != null) input.min = f.minimum;
        if (f.maximum != null) input.max = f.maximum;
        input.value = trig[f.id] != null ? trig[f.id] : '';
      }
      input.dataset.trig = String(ix);
      input.dataset.field = f.id;
      input.addEventListener('change', () => {
        const raw = input.value;
        const n = Number(raw);
        if (f.type === 'integer' && Number.isFinite(n)) {
          nodeModalTriggers[ix][f.id] = n;
        } else {
          nodeModalTriggers[ix][f.id] = raw;
        }
        if (f.id === 'label') {
          const strong = row.querySelector('.nodes-trigger-row-head strong');
          if (strong) strong.textContent = raw || ('Config #' + (ix + 1));
        }
      });
      control.appendChild(input);
      if (f.testable) {
        const testBtn = document.createElement('button');
        testBtn.type = 'button';
        testBtn.className = 'btn btn-secondary btn-small';
        testBtn.textContent = f.id === 'brightness' ? 'Test level' : 'Test angle';
        testBtn.onclick = () => testTriggerValue(ix, f);
        control.appendChild(testBtn);
      }
      wrap.appendChild(control);
      grid.appendChild(wrap);
    });
    row.appendChild(grid);
    host.appendChild(row);
  });
}

function collectNodeTriggers() {
  return nodeModalTriggers.map((t, ix) => {
    const copy = Object.assign({}, t);
    copy.dcc = Number(copy.dcc) || 0;
    copy.direction = copy.direction || 'Forward';
    if (copy.deg != null) copy.deg = Number(copy.deg);
    if (copy.brightness != null) copy.brightness = Number(copy.brightness);
    if (Array.isArray(copy.on) || Array.isArray(copy.lamps) || String(copy.state || '').startsWith('config')) {
      // Normalise from current per-pin levels
      const pinCount = (nodesUi._modalPins || []).length;
      const on = [];
      const flashing = [];
      for (let i = 0; i < pinCount; i++) {
        const lvl = pinLevelForConfig(copy, i);
        if (lvl === 'on') on.push(i);
        else if (lvl === 'flashing') {
          on.push(i);
          flashing.push(i);
        }
      }
      copy.on = on;
      copy.flashing = flashing;
      copy.state = 'config_' + (ix + 1);
      copy.label = copy.label || ('Config #' + (ix + 1));
      delete copy.lamps;
      delete copy.route;
      delete copy.aspect;
    }
    return copy;
  });
}

function renderSensorLedGroup(title, channels) {
  if (!channels.length) return '';
  const leds = channels.map((ch) => {
    const on = ch.state === 1;
    const kind = ch.kind === 'virtual' ? 'kind-virtual'
      : (ch.kind === 'digital' ? 'kind-digital' : 'kind-analog');
    const stateLabel = ch.kind === 'virtual'
      ? (on ? 'ACTIVE' : 'CLEAR')
      : (on ? 'DETECTED' : 'CLEAR');
    return '<div class="sensors-led ' + kind + (on ? ' on' : '') + '" title="' +
      (ch.id || '') + ': ' + stateLabel + '">' +
      '<span class="sensors-led-lamp" aria-hidden="true"></span>' +
      '<span class="sensors-led-label">' + (ch.label || '') + '</span>' +
      '</div>';
  }).join('');
  return '<div class="sensors-led-group">' +
    '<div class="sensors-led-group-label">' + title + '</div>' +
    '<div class="sensors-led-row">' + leds + '</div></div>';
}

function renderSensorLedPanel(channels) {
  const list = channels || [];
  const optical = list.filter((c) => c.kind !== 'digital' && c.kind !== 'virtual');
  const digital = list.filter((c) => c.kind === 'digital');
  const virtual = list.filter((c) => c.kind === 'virtual');
  if (!optical.length && !digital.length && !virtual.length) {
    return '<p class="wifi-hint">No channel map yet (waiting for prefix / READY).</p>';
  }
  return '<div class="sensors-led-panel">' +
    renderSensorLedGroup('Analogue', optical) +
    renderSensorLedGroup('Digital', digital) +
    renderSensorLedGroup('Virtual', virtual) +
    '</div>';
}

/** Input channels not claimed by a configured output (virtual always shown). */
function boardInputChannels(board) {
  const usage = boardPinUsage(board);
  return (board.channels || []).filter((ch) => {
    if (ch.kind === 'virtual') return true;
    const label = (ch.label || '').toUpperCase();
    if (!label) return true;
    return usage[label]?.role !== 'output';
  });
}

function triggerStateLabel(t, type, ix) {
  if (t?.label) return String(t.label);
  if (t?.state) {
    const s = String(t.state);
    return s.charAt(0).toUpperCase() + s.slice(1);
  }
  if (type === 'fade') return 'Level ' + (ix + 1);
  if (type === 'servo') return 'Pos ' + (ix + 1);
  return 'State ' + (ix + 1);
}

/** Forward → closed/ON; Reverse → thrown/OFF (matches layout wire). */
function triggerDirectionClosed(direction) {
  const d = String(direction || 'Forward').toLowerCase();
  if (d.startsWith('rev') || d === 'thrown' || d === 'off') return false;
  return true;
}

function outputLatchId(boardId, outIx) {
  return String(boardId || '') + '#' + outIx;
}

function outputControlTriggers(o) {
  if (Array.isArray(o.triggers) && o.triggers.length) return o.triggers;
  if (o.dcc != null) {
    return [
      { dcc: o.dcc, direction: 'Forward', label: 'On' },
      { dcc: o.dcc, direction: 'Reverse', label: 'Off' }
    ];
  }
  return [];
}

function renderOutputStateButtonsHtml(o, boardId, outIx) {
  const latchId = outputLatchId(boardId, outIx);
  const latched = nodesUi.lastAccessoryByOutput[latchId] || null;
  return outputControlTriggers(o).map((t, ix) => {
    const dcc = Number(t.dcc) || 0;
    const closed = triggerDirectionClosed(t.direction);
    const key = dcc + ':' + (closed ? 'on' : 'off');
    const active = latched === key;
    return '<button type="button" class="btn btn-secondary btn-small nodes-func-state' +
      (active ? ' is-active' : '') + '"' +
      ' data-acc-dcc="' + dcc + '"' +
      ' data-acc-closed="' + (closed ? '1' : '0') + '"' +
      ' data-acc-key="' + escapeHtml(key) + '"' +
      ' data-out-latch="' + escapeHtml(latchId) + '">' +
      escapeHtml(triggerStateLabel(t, o.type, ix)) + '</button>';
  }).join('');
}

function renderOutputFuncChip(o, boardId, outIx) {
  const latchId = outputLatchId(boardId, outIx);
  const btns = renderOutputStateButtonsHtml(o, boardId, outIx);
  return '<div class="nodes-func type-' + escapeHtml(o.type || 'unknown') +
    '" data-out-latch="' + escapeHtml(latchId) + '">' +
    '<div class="nodes-func-head">' +
    '<strong>' + escapeHtml(outputDisplayName(o)) + '</strong>' +
    '<span class="nodes-func-type">' + escapeHtml(outputLabel(o.type)) + '</span>' +
    '</div>' +
    (btns
      ? '<div class="nodes-func-states">' + btns + '</div>'
      : '<p class="nodes-io-empty">No DCC triggers</p>') +
    '</div>';
}

function renderAssignedOutputCard(board, o, ix, state) {
  const latchId = outputLatchId(board.id, ix);
  const btns = renderOutputStateButtonsHtml(o, board.id, ix);
  const badge = state?.label
    ? '<span class="nodes-out-badge' + (state.animating ? ' is-animating' : '') + '">' +
      escapeHtml(state.label) + '</span>'
    : '';
  const findOn = nodesUi.highlightOutIx === ix;
  const card = document.createElement('div');
  card.className = 'nodes-output-card' + (findOn ? ' is-finding' : '');
  card.dataset.outIx = String(ix);
  card.dataset.outLatch = latchId;
  card.innerHTML =
    '<div class="nodes-output-row-head">' +
    '<strong>' + escapeHtml(outputDisplayName(o)) + '</strong>' + badge +
    '<span class="nodes-output-tools">' +
    '<button type="button" class="nodes-icon-btn" data-out-find title="Highlight pins on board" aria-label="Highlight pins">' +
    '<span aria-hidden="true">⌕</span></button>' +
    '<button type="button" class="nodes-icon-btn" data-out-cfg title="Configure" aria-label="Configure">' +
    '<span aria-hidden="true">⚙</span></button>' +
    '</span></div>' +
    (btns ? '<div class="nodes-func-states">' + btns + '</div>' : '<p class="nodes-io-empty">No DCC triggers</p>') +
    '<div class="nodes-output-meta">' + escapeHtml(outputLabel(o.type)) +
    ' · ' + escapeHtml(triggerSummary(o)) + '</div>';
  card.querySelector('[data-out-cfg]')?.addEventListener('click', (ev) => {
    ev.stopPropagation();
    openOutputModal(board, ix);
  });
  card.querySelector('[data-out-find]')?.addEventListener('click', (ev) => {
    ev.stopPropagation();
    nodesUi.highlightDistIx = null;
    nodesUi.highlightOutIx = (nodesUi.highlightOutIx === ix) ? null : ix;
    applyPinHighlights(board);
    document.querySelectorAll('#nodesOutputList .nodes-output-card').forEach((el) => {
      el.classList.toggle('is-finding', Number(el.dataset.outIx) === nodesUi.highlightOutIx);
    });
    document.querySelectorAll('#nodesDistrictList .nodes-output-card').forEach((el) => {
      el.classList.remove('is-finding');
    });
  });
  return card;
}

function applyPinHighlights(board) {
  const want = new Set();
  if (nodesUi.highlightOutIx != null && board) {
    const o = (board.outputs || [])[nodesUi.highlightOutIx];
    (o?.pins || []).forEach((p) => want.add(String(p).toUpperCase()));
    if (o?.type === 'level_crossing') {
      const v = normalizeVirtualLabelUi(o.params?.feedbackVirtual);
      if (v) want.add(v);
    }
  }
  if (nodesUi.highlightDistIx != null && board) {
    const d = boardOpticalDistricts(board)[nodesUi.highlightDistIx];
    if (d?.ref) want.add(String(d.ref).toUpperCase());
    (d?.sensors || []).forEach((p) => want.add(String(p).toUpperCase()));
  }
  document.querySelectorAll('#nodesBoardDiagram button.nodes-pin[data-pin]').forEach((btn) => {
    btn.classList.toggle('is-pin-find', want.has(btn.dataset.pin));
  });
}

function renderCompactInputLeds(channels) {
  if (!channels.length) {
    return '<p class="nodes-io-empty">No free input channels</p>';
  }
  const optical = channels.filter((c) => c.kind !== 'digital' && c.kind !== 'virtual');
  const digital = channels.filter((c) => c.kind === 'digital');
  const virtual = channels.filter((c) => c.kind === 'virtual');
  const renderRow = (list, kindClass) => list.map((ch) => {
    const on = ch.state === 1;
    const stateLabel = ch.kind === 'virtual' ? (on ? 'ACTIVE' : 'CLEAR') : (on ? 'DETECTED' : 'CLEAR');
    return '<div class="sensors-led ' + kindClass + (on ? ' on' : '') + '" title="' +
      escapeHtml(ch.id || '') + ': ' + stateLabel + '">' +
      '<span class="sensors-led-lamp" aria-hidden="true"></span>' +
      '<span class="sensors-led-label">' + escapeHtml(ch.label || '') + '</span>' +
      '</div>';
  }).join('');
  return '<div class="nodes-io-leds">' +
    renderRow(optical, 'kind-analog') +
    renderRow(digital, 'kind-digital') +
    renderRow(virtual, 'kind-virtual') +
    '</div>';
}

/** View-screen I/O summary: inputs vs configured output functions. */
function renderBoardIoStatus(board) {
  const inputs = boardInputChannels(board);
  const outputs = board.outputs || [];
  const districts = boardOpticalDistricts(board);
  const inBody = (board.channels && board.channels.length)
    ? renderCompactInputLeds(inputs)
    : '<p class="nodes-io-empty">Waiting for channel map…</p>';
  const outBody = outputs.length
    ? '<div class="nodes-io-funcs">' +
      outputs.map((o, ix) => renderOutputFuncChip(o, board.id, ix)).join('') +
      '</div>'
    : '<p class="nodes-io-empty">No outputs configured</p>';
  const distBody = districts.length
    ? '<div class="nodes-io-funcs">' +
      districts.map((d) => {
        const sens = (d.sensors || []).join(', ') || '—';
        return '<div class="nodes-func">' +
          '<div class="nodes-func-title">' + escapeHtml(d.name) + '</div>' +
          '<div class="nodes-func-meta">Ref ' + escapeHtml(d.ref || '—') +
          ' · ' + escapeHtml(sens) + ' · ' + clampSensitivityPct(d.sensitivityPct) + '%</div>' +
          '</div>';
      }).join('') +
      '</div>'
    : '<p class="nodes-io-empty">No sensor districts</p>';
  return '<div class="nodes-io">' +
    '<div class="nodes-io-col nodes-io-in">' +
    '<div class="nodes-io-label">Inputs</div>' + inBody + '</div>' +
    '<div class="nodes-io-col nodes-io-out">' +
    '<div class="nodes-io-label">Outputs</div>' + outBody + '</div>' +
    '<div class="nodes-io-col nodes-io-dist">' +
    '<div class="nodes-io-label">Optical districts</div>' + distBody + '</div>' +
    '</div>';
}

function bindViewOutputControls(root) {
  if (!root) return;
  root.querySelectorAll('button.nodes-func-state[data-acc-dcc]').forEach((btn) => {
    if (btn.dataset.boundAcc === '1') return;
    btn.dataset.boundAcc = '1';
    btn.addEventListener('click', () => {
      const dcc = Number(btn.getAttribute('data-acc-dcc'));
      const closed = btn.getAttribute('data-acc-closed') === '1';
      const key = btn.getAttribute('data-acc-key') || (dcc + ':' + (closed ? 'on' : 'off'));
      const latchId = btn.getAttribute('data-out-latch') || '';
      if (!sendAccessoryAddress(dcc, closed)) return;
      if (latchId) nodesUi.lastAccessoryByOutput[latchId] = key;
      const scope = btn.closest('.nodes-func, .nodes-output-card') || btn.parentElement;
      if (scope) {
        scope.querySelectorAll('button.nodes-func-state.is-active').forEach((el) => {
          el.classList.remove('is-active');
        });
        btn.classList.add('is-active');
      }
      // Keep other surfaces (view chip / configure card) in sync for same latch
      document.querySelectorAll('button.nodes-func-state[data-out-latch="' + latchId + '"]').forEach((el) => {
        el.classList.toggle('is-active', el.getAttribute('data-acc-key') === key);
      });
      showStatus('DCC ' + dcc + ' ' + (closed ? 'Forward/ON' : 'Reverse/OFF'), 'success');
    });
  });
}

function outputLabel(typeId) {
  return outputTypeMeta(typeId)?.label || typeId || '?';
}

function escapeHtml(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function outputDisplayName(o) {
  const n = (o && o.name != null ? String(o.name) : '').trim();
  return n || outputLabel(o?.type);
}

function suggestOutputName(board, type) {
  const base = outputLabel(type);
  const used = new Set((board.outputs || []).map((o) => (o.name || '').trim().toLowerCase()).filter(Boolean));
  if (!used.has(base.toLowerCase())) return base;
  let i = 2;
  while (used.has((base + ' ' + i).toLowerCase())) i++;
  return base + ' ' + i;
}

function boardOutputSummary(board) {
  const outs = board.outputs || [];
  if (!outs.length) return 'No outputs configured';
  return outs.map((o) => outputDisplayName(o) + ' · ' + outputLabel(o.type) + ' · ' +
    triggerSummary(o) + ' [' + (o.pins || []).join(', ') + ']').join(' · ');
}

async function loadSensorFlashPorts() {
  const select = document.getElementById('sensorFlashPort');
  if (!select) return;
  try {
    const res = await fetch('/api/ports');
    const data = await res.json();
    const prev = select.value;
    select.innerHTML = '<option value="">Select port...</option>';
    (data.ports || []).forEach((p) => {
      const opt = document.createElement('option');
      opt.value = p;
      opt.textContent = p;
      select.appendChild(opt);
    });
    if (prev && (data.ports || []).includes(prev)) select.value = prev;
  } catch (err) { /* ignore */ }
}

async function loadSensorsToolchain() {
  const el = document.getElementById('sensorsToolchainStatus');
  if (!el) return;
  try {
    const res = await fetch('/api/sensors/toolchain');
    const data = await res.json();
    if (data.arduinoCli && data.fqbnReady) {
      el.textContent = 'Ready (' + data.fqbn + ')';
      el.style.color = '#0a7a2f';
    } else if (data.arduinoCli) {
      el.textContent = 'arduino-cli present; AVR core missing — run: sudo panelsdcc-connect-arduino-install';
      el.style.color = '#a05a00';
    } else {
      el.textContent = 'Missing — run: sudo panelsdcc-connect-arduino-install';
      el.style.color = '#a00';
    }
  } catch (err) {
    el.textContent = 'Unable to check toolchain';
  }
}

async function loadSensorsBoards() {
  try {
    const res = await fetch('/api/sensors');
    const data = await res.json();
    sensorsPageCache = data;
    fillBoardTypeSelects(data.boardProfiles || []);
    if (data.suggestedPrefix) {
      const shim = document.getElementById('shimBoardPrefix');
      const flash = document.getElementById('sensorFlashPrefix');
      if (shim && !shim.value) shim.value = data.suggestedPrefix;
      if (flash && !flash.value) flash.value = data.suggestedPrefix;
    }
    (data.boards || []).forEach((b) => syncViewLatchFromOutputStates(b));
    if (nodesUi.screen === 'view') renderNodesView();
    else if (nodesUi.screen === 'configure') renderNodesConfigure();
    else if (nodesUi.screen === 'audit') renderNodesAudit();
    else if (nodesUi.screen === 'add' && nodesUi.addStep === 'details') refreshNodesAddDetails();
  } catch (err) {
    const list = document.getElementById('sensorsBoardList');
    if (list) list.innerHTML = '<p class="wifi-hint">Failed to load boards: ' + err.message + '</p>';
  }
}

/** Flat list of every DCC trigger across all node outputs. */
function collectDccAuditEntries(boards) {
  const entries = [];
  (boards || []).forEach((board) => {
    (board.outputs || []).forEach((o, outIx) => {
      outputControlTriggers(o).forEach((t, tIx) => {
        const dcc = Number(t.dcc);
        if (!dcc || dcc < 1) return;
        entries.push({
          dcc,
          boardId: board.id,
          boardLabel: board.prefix || board.id,
          outputIx: outIx,
          outputName: outputDisplayName(o),
          typeLabel: outputLabel(o.type),
          configLabel: triggerStateLabel(t, o.type, tIx),
          direction: t.direction || 'Forward',
          pins: (o.pins || []).join(', ') || '—'
        });
      });
    });
  });
  return entries;
}

function buildDccAuditGroups(entries) {
  const groups = new Map();
  entries.forEach((e) => {
    if (!groups.has(e.dcc)) groups.set(e.dcc, []);
    groups.get(e.dcc).push(e);
  });
  // Same DCC + direction used by more than one distinct function → clash
  const clashKeys = new Set();
  const dirBuckets = new Map();
  entries.forEach((e) => {
    const dirKey = e.dcc + ':' + String(e.direction || '').toLowerCase();
    const outKey = e.boardId + '#' + e.outputIx;
    if (!dirBuckets.has(dirKey)) dirBuckets.set(dirKey, new Set());
    dirBuckets.get(dirKey).add(outKey);
  });
  dirBuckets.forEach((outs, dirKey) => {
    if (outs.size > 1) clashKeys.add(dirKey);
  });
  return { groups, clashKeys };
}

function renderNodesAudit() {
  const body = document.getElementById('nodesAuditTableBody');
  const empty = document.getElementById('nodesAuditEmpty');
  const summary = document.getElementById('nodesAuditSummary');
  if (!body) return;
  const q = (document.getElementById('nodesAuditSearch')?.value || '').trim().toLowerCase();
  let entries = collectDccAuditEntries(sensorsPageCache?.boards || []);
  if (q) {
    entries = entries.filter((e) =>
      String(e.dcc).includes(q) ||
      String(e.boardLabel).toLowerCase().includes(q) ||
      String(e.outputName).toLowerCase().includes(q) ||
      String(e.typeLabel).toLowerCase().includes(q) ||
      String(e.configLabel).toLowerCase().includes(q) ||
      String(e.direction).toLowerCase().includes(q) ||
      String(e.pins).toLowerCase().includes(q));
  }
  const { groups, clashKeys } = buildDccAuditGroups(entries);
  const dccs = Array.from(groups.keys()).sort((a, b) => a - b);
  let sharedCount = 0;
  let clashCount = 0;
  if (!dccs.length) {
    body.innerHTML = '';
    if (empty) empty.hidden = false;
    if (summary) summary.textContent = q ? 'No matches.' : 'No DCC addresses in use.';
    return;
  }
  if (empty) empty.hidden = true;
  body.innerHTML = dccs.map((dcc) => {
    const items = groups.get(dcc);
    const shared = items.length > 1;
    const clash = items.some((e) =>
      clashKeys.has(e.dcc + ':' + String(e.direction || '').toLowerCase()));
    if (shared) sharedCount++;
    if (clash) clashCount++;
    const cell = (fn) => items.map((e) =>
      '<div class="nodes-audit-cell-item" data-board-id="' + escapeHtml(e.boardId) +
      '" data-out-ix="' + e.outputIx + '" title="Open configure">' +
      escapeHtml(fn(e)) + '</div>').join('');
    return '<tr class="' + (clash ? 'is-clash' : (shared ? 'is-shared' : '')) + '">' +
      '<td class="nodes-audit-dcc">' + dcc + '</td>' +
      '<td>' + cell((e) => e.boardLabel) + '</td>' +
      '<td>' + cell((e) => e.outputName) + '</td>' +
      '<td>' + cell((e) => e.typeLabel) + '</td>' +
      '<td>' + cell((e) => e.configLabel) + '</td>' +
      '<td>' + cell((e) => e.direction) + '</td>' +
      '<td>' + cell((e) => e.pins) + '</td>' +
      '</tr>';
  }).join('');
  if (summary) {
    summary.textContent = dccs.length + ' address' + (dccs.length === 1 ? '' : 'es') +
      ' · ' + sharedCount + ' shared · ' + clashCount + ' clash' + (clashCount === 1 ? '' : 'es');
  }
  body.querySelectorAll('.nodes-audit-cell-item[data-board-id]').forEach((el) => {
    el.addEventListener('click', () => {
      const id = el.getAttribute('data-board-id');
      if (id) setPageHash(nodesHash('configure', id));
    });
  });
}

function renderNodesView() {
  const list = document.getElementById('sensorsBoardList');
  const strip = document.getElementById('sensorsCandidateStrip');
  const stubs = document.getElementById('sensorsTransportStubs');
  if (!list || !sensorsPageCache) return;
  const boards = sensorsPageCache.boards || [];
  const candidates = sensorsPageCache.candidates || [];

  if (strip) {
    if (!candidates.length) {
      strip.hidden = true;
      strip.innerHTML = '';
    } else {
      strip.hidden = false;
      strip.innerHTML = '<strong>' + candidates.length + ' USB candidate' + (candidates.length > 1 ? 's' : '') +
        '</strong> waiting to be probed. ' +
        '<button type="button" class="btn btn-secondary btn-small" id="btnNodesGoProbe">Add via probe</button>';
      document.getElementById('btnNodesGoProbe')?.addEventListener('click', () => {
        nodesUi.addMethod = 'probe';
        nodesUi.addStep = 'details';
        setPageHash(nodesHash('add'));
      });
    }
  }

    if (!boards.length) {
    list.innerHTML = '<div class="nodes-empty">' +
      '<p>No boards yet.</p>' +
      '<button type="button" class="btn btn-primary" id="btnNodesEmptyAdd">Add node</button></div>';
    document.getElementById('btnNodesEmptyAdd')?.addEventListener('click', () => {
      nodesUi.addMethod = null;
      nodesUi.addStep = 'method';
      setPageHash(nodesHash('add'));
    });
    } else {
      list.innerHTML = '';
      boards.forEach((b) => {
        const row = document.createElement('div');
      row.className = 'sensors-board-row nodes-view-card';
      const badge = b.virtual ? ' <span class="wifi-mode-badge">Virtual</span>' : '';
        row.innerHTML =
          '<div class="sensors-board-header">' +
        '<div><strong>Prefix ' + (b.prefix || '—') + '</strong>' + badge +
        '<div class="nodes-card-sub">' + (b.boardType || '') + ' · ' + (b.transport || 'usb') +
        ' · <code>' + (b.endpoint || '') + '</code> · ' + (b.status || '') + '</div></div>' +
          '<div class="sensors-board-actions"></div></div>' +
        renderBoardIoStatus(b);
      bindViewOutputControls(row);
        const actions = row.querySelector('.sensors-board-actions');
      const cfg = document.createElement('button');
      cfg.className = 'btn btn-primary btn-small';
      cfg.textContent = 'Configure';
      cfg.onclick = () => setPageHash(nodesHash('configure', b.id));
      actions.appendChild(cfg);
      if (b.virtual) {
        const rem = document.createElement('button');
        rem.className = 'btn btn-secondary btn-small';
        rem.textContent = 'Remove';
        rem.onclick = () => removeShimBoard(b.id);
        actions.appendChild(rem);
      } else if (b.status === 'listening') {
          const rel = document.createElement('button');
          rel.className = 'btn btn-secondary btn-small';
          rel.textContent = 'Release port';
          rel.onclick = () => releaseSensorBoard(b.id);
          actions.appendChild(rel);
        } else if (b.status === 'released' || b.status === 'flashing') {
          const resume = document.createElement('button');
        resume.className = 'btn btn-secondary btn-small';
          resume.textContent = 'Resume';
          resume.disabled = b.status === 'flashing';
          resume.onclick = () => resumeSensorBoard(b.id);
          actions.appendChild(resume);
        }
        list.appendChild(row);
      });
    }

  if (stubs && sensorsPageCache.transports) {
      stubs.innerHTML = '';
    sensorsPageCache.transports.forEach((t) => {
      if (t.id === 'usb' || t.id === 'shim' || t.id === 'ble') return;
      if (t.available) return;
        const card = document.createElement('div');
        card.className = 'sensors-stub-card';
        card.innerHTML = '<strong>' + (t.label || t.id) + '</strong><br><span class="wifi-hint">' +
        (t.note || 'Coming later') + '</span>';
        stubs.appendChild(card);
      });
    }
}

function setAddStep(step) {
  nodesUi.addStep = step;
  document.querySelectorAll('#nodesAddSteps li').forEach((li) => {
    const s = li.getAttribute('data-step');
    li.classList.toggle('is-current', s === step);
    li.classList.toggle('is-done',
      ['method', 'details', 'action', 'done'].indexOf(s) <
      ['method', 'details', 'action', 'done'].indexOf(step));
  });
  document.querySelectorAll('.nodes-add-panel').forEach((panel) => {
    panel.hidden = panel.getAttribute('data-add-step') !== step;
  });
}

function renderNodesAdd() {
  setAddStep(nodesUi.addStep || 'method');
  if (nodesUi.addStep === 'details') renderNodesAddDetailsBody();
  if (nodesUi.addStep === 'action') prepareNodesAddAction();
}

function addDetailsControlFocused() {
  const active = document.activeElement;
  const body = document.getElementById('nodesAddDetailsBody');
  if (!active || !body || !body.contains(active)) return false;
  const tag = active.tagName;
  return tag === 'SELECT' || tag === 'INPUT' || tag === 'TEXTAREA';
}

function probeCandidateKey() {
  return (sensorsPageCache?.candidates || [])
    .map((c) => (c.port || c.endpoint || '') + '|' + (c.name || ''))
    .join('\n');
}

/** Poll must not rebuild the flash form — that closes open selects. */
function refreshNodesAddDetails() {
  const body = document.getElementById('nodesAddDetailsBody');
  const method = nodesUi.addMethod || '';
  if (!body) return;
  if (body.getAttribute('data-add-method') !== method) {
    renderNodesAddDetailsBody();
    return;
  }
  if (addDetailsControlFocused()) return;
  if (method === 'usb') syncAddUsbPortOptions();
  else if (method === 'probe' && body.getAttribute('data-probe-key') !== probeCandidateKey()) {
    renderNodesAddDetailsBody();
  }
}

function syncAddUsbPortOptions() {
  const sel = document.getElementById('addUsbPort');
  if (!sel || document.activeElement === sel) return;
  const ports = Array.from(document.getElementById('sensorFlashPort')?.options || [])
    .filter((o) => o.value)
    .map((o) => ({ value: o.value, text: o.textContent }));
  const existing = Array.from(sel.options).filter((o) => o.value).map((o) => o.value);
  if (ports.length === existing.length && ports.every((p, i) => p.value === existing[i])) return;
  const prev = sel.value;
  sel.innerHTML = '<option value="">Select port…</option>';
  ports.forEach((p) => {
    const opt = document.createElement('option');
    opt.value = p.value;
    opt.textContent = p.text;
    sel.appendChild(opt);
  });
  if (prev && ports.some((p) => p.value === prev)) sel.value = prev;
}

function renderNodesAddDetailsBody() {
  const body = document.getElementById('nodesAddDetailsBody');
  const title = document.getElementById('nodesAddDetailsTitle');
  if (!body) return;
  // Poll remounts this form — keep in-progress field values.
  const draft = {
    usbPort: document.getElementById('addUsbPort')?.value,
    usbType: document.getElementById('addUsbType')?.value || nodesUi.addDraft?.usbType,
    usbPrefix: document.getElementById('addUsbPrefix')?.value || nodesUi.addDraft?.usbPrefix,
    shimType: document.getElementById('addShimType')?.value || nodesUi.addDraft?.shimType,
    shimPrefix: document.getElementById('addShimPrefix')?.value || nodesUi.addDraft?.shimPrefix,
    btPrefix: document.getElementById('addBtPrefix')?.value || nodesUi.addDraft?.btPrefix,
    probePort: document.querySelector('input[name="addProbePort"]:checked')?.value
      || nodesUi.addDraft?.probePort
  };
  const suggested = sensorsPageCache?.suggestedPrefix || 'A';
  const profiles = sensorsPageCache?.boardProfiles || [];
  const profileOpts = (selected) => profiles.map((p) =>
    '<option value="' + p.id + '"' + (p.id === selected ? ' selected' : '') + '>' +
    (p.label || p.id) + '</option>').join('');

  if (nodesUi.addMethod === 'usb') {
    if (title) title.textContent = 'Flash USB board';
    const ports = Array.from(document.getElementById('sensorFlashPort')?.options || [])
      .filter((o) => o.value)
      .map((o) => '<option value="' + o.value + '"' +
        (o.value === draft.usbPort ? ' selected' : '') + '>' + o.textContent + '</option>')
      .join('');
    const type = draft.usbType || 'nano-avr';
    const prefix = draft.usbPrefix || suggested;
    body.innerHTML =
      '<div class="form-group"><label>Port</label><select id="addUsbPort"><option value="">Select port…</option>' +
      ports + '</select></div>' +
      '<div class="form-group"><label>Board type</label><select id="addUsbType">' +
      profileOpts(type) + '</select></div>' +
      '<div class="form-group"><label>Prefix (A–ZZ)</label>' +
      '<input type="text" id="addUsbPrefix" maxlength="2" value="' + escapeHtml(prefix) +
      '" style="text-transform:uppercase;"></div>' +
      '<p class="wifi-hint" id="addUsbHint"></p>';
    const persistUsb = () => {
      nodesUi.addDraft = Object.assign({}, nodesUi.addDraft, {
        usbType: document.getElementById('addUsbType')?.value,
        usbPrefix: document.getElementById('addUsbPrefix')?.value,
        usbPort: document.getElementById('addUsbPort')?.value
      });
      updateAddUsbHint();
    };
    document.getElementById('addUsbType')?.addEventListener('change', persistUsb);
    document.getElementById('addUsbPrefix')?.addEventListener('input', persistUsb);
    document.getElementById('addUsbPort')?.addEventListener('change', persistUsb);
    updateAddUsbHint();
  } else if (nodesUi.addMethod === 'virtual') {
    if (title) title.textContent = 'Virtual board';
    const type = draft.shimType || 'nano-avr';
    const prefix = draft.shimPrefix || suggested;
    body.innerHTML =
      '<div class="form-group"><label>Board type</label><select id="addShimType">' +
      profileOpts(type) + '</select></div>' +
      '<div class="form-group"><label>Prefix (A–ZZ)</label>' +
      '<input type="text" id="addShimPrefix" maxlength="2" value="' + escapeHtml(prefix) +
      '" style="text-transform:uppercase;"></div>' +
      '<p class="wifi-hint">Creates an in-process board (Nano or Mega) so you can exercise Configure without hardware.</p>';
    const persistShim = () => {
      nodesUi.addDraft = Object.assign({}, nodesUi.addDraft, {
        shimType: document.getElementById('addShimType')?.value,
        shimPrefix: document.getElementById('addShimPrefix')?.value
      });
    };
    document.getElementById('addShimType')?.addEventListener('change', persistShim);
    document.getElementById('addShimPrefix')?.addEventListener('input', persistShim);
  } else if (nodesUi.addMethod === 'bluetooth') {
    if (title) title.textContent = 'Bluetooth (ESP32)';
    const prefix = draft.btPrefix || suggested;
    body.innerHTML =
      '<p class="wifi-hint">Scan for PanelsDCC-* devices (Pi / BlueZ). Pairing opens a session when available.</p>' +
      '<div class="nodes-inline-actions">' +
      '<button type="button" class="btn btn-secondary" id="btnAddBtScan">Scan now</button></div>' +
      '<div id="addBtResults" class="nodes-bt-results"></div>' +
      '<div class="form-group"><label>Prefix for new pair (A–ZZ)</label>' +
      '<input type="text" id="addBtPrefix" maxlength="2" value="' + escapeHtml(prefix) +
      '" style="text-transform:uppercase;"></div>';
    document.getElementById('btnAddBtScan')?.addEventListener('click', runAddBluetoothScan);
    document.getElementById('addBtPrefix')?.addEventListener('input', () => {
      nodesUi.addDraft = Object.assign({}, nodesUi.addDraft, {
        btPrefix: document.getElementById('addBtPrefix')?.value
      });
    });
  } else if (nodesUi.addMethod === 'probe') {
    if (title) title.textContent = 'Probe USB candidate';
    const cands = sensorsPageCache?.candidates || [];
    if (!cands.length) {
      body.innerHTML = '<p class="wifi-hint">No USB candidates right now. Plug in a board or flash first.</p>';
    } else {
      body.innerHTML = '<div class="nodes-probe-list">' + cands.map((c) => {
        const port = c.port || c.endpoint || '';
        const checked = port === draft.probePort ? ' checked' : '';
        return '<label class="nodes-probe-row"><input type="radio" name="addProbePort" value="' +
          port + '"' + checked + '> <code>' + port + '</code> ' + (c.name || '') + '</label>';
      }).join('') + '</div>';
      body.querySelectorAll('input[name="addProbePort"]').forEach((el) => {
        el.addEventListener('change', () => {
          nodesUi.addDraft = Object.assign({}, nodesUi.addDraft, { probePort: el.value });
        });
      });
    }
  }
  body.setAttribute('data-add-method', nodesUi.addMethod || '');
  if (nodesUi.addMethod === 'probe') body.setAttribute('data-probe-key', probeCandidateKey());
  else body.removeAttribute('data-probe-key');
}

function updateAddUsbHint() {
  const el = document.getElementById('addUsbHint');
  if (!el) return;
  const prefix = (document.getElementById('addUsbPrefix')?.value || '').trim().toUpperCase();
  const type = document.getElementById('addUsbType')?.value || 'nano-avr';
  if (!/^[A-Z]{1,2}$/.test(prefix)) {
    el.textContent = 'Enter a valid prefix (A–Z or AA–ZZ).';
    return;
  }
  if (type.indexOf('mega') >= 0) {
    el.textContent = 'Default Mega: optical ' + prefix + '-A0…A14 · digital ' + prefix + '-D22…D37';
  } else {
    el.textContent = 'Default Nano: optical ' + prefix + '-A0…A6 · digital ' + prefix + '-D2…D12';
  }
}

function prepareNodesAddAction() {
  const title = document.getElementById('nodesAddActionTitle');
  const logEl = document.getElementById('sensorFlashLog');
  const runBtn = document.getElementById('btnNodesAddRun');
  if (logEl) logEl.textContent = '';
  if (nodesUi.addMethod === 'usb') {
    if (title) title.textContent = 'Flash board';
    if (runBtn) runBtn.textContent = 'Flash';
  } else if (nodesUi.addMethod === 'virtual') {
    if (title) title.textContent = 'Create virtual board';
    if (runBtn) runBtn.textContent = 'Create';
  } else if (nodesUi.addMethod === 'probe') {
    if (title) title.textContent = 'Probe port';
    if (runBtn) runBtn.textContent = 'Probe';
  } else if (nodesUi.addMethod === 'bluetooth') {
    if (title) title.textContent = 'Pair Bluetooth';
    if (runBtn) runBtn.textContent = 'Pair selected';
  }
}

async function runAddBluetoothScan() {
  const host = document.getElementById('addBtResults');
  if (host) host.textContent = 'Scanning…';
  try {
    const res = await fetch('/api/sensors/bluetooth/scan', { method: 'POST' });
    const data = await readJsonOrError(res);
    const devices = data.devices || [];
    if (!host) return;
    if (!devices.length) {
      host.innerHTML = '<p class="wifi-hint">No PanelsDCC-* devices found (Pi/BlueZ only).</p>';
      return;
    }
    host.innerHTML = devices.map((d) =>
      '<label class="nodes-probe-row"><input type="radio" name="addBtAddr" value="' +
      (d.address || '') + '"> ' + (d.name || d.address) + '</label>').join('');
  } catch (err) {
    if (host) host.innerHTML = '<p class="wifi-hint">Scan failed: ' + err.message + '</p>';
  }
}

async function runNodesAddAction() {
  const logEl = document.getElementById('sensorFlashLog');
  try {
    if (nodesUi.addMethod === 'usb') {
      const port = document.getElementById('addUsbPort')?.value;
      const prefix = (document.getElementById('addUsbPrefix')?.value || '').trim().toUpperCase();
      const boardType = document.getElementById('addUsbType')?.value || 'nano-avr';
      if (!port) throw new Error('Select a port');
      if (!/^[A-Z]{1,2}$/.test(prefix)) throw new Error('Prefix must be A–Z or AA–ZZ');
      const res = await fetch('/api/sensors/flash', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ port, prefix, boardType })
      });
      const data = await readJsonOrError(res);
      if (!res.ok && res.status !== 202) throw new Error(data.error || res.statusText);
      showStatus('Flash started', 'success');
      if (sensorFlashPollTimer) clearInterval(sensorFlashPollTimer);
      sensorFlashPollTimer = setInterval(() => pollSensorFlashStatus(prefix), 1000);
      pollSensorFlashStatus(prefix);
      return;
    }
    if (nodesUi.addMethod === 'virtual') {
      const prefix = (document.getElementById('addShimPrefix')?.value || '').trim().toUpperCase();
      const boardType = document.getElementById('addShimType')?.value || 'nano-avr';
      const res = await fetch('/api/sensors/shim', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ prefix: prefix || undefined, boardType })
      });
      const data = await readJsonOrError(res);
      if (!res.ok) throw new Error(data.error || res.statusText);
      showStatus('Virtual board ' + data.prefix, 'success');
      await loadSensorsBoards();
      const board = (sensorsPageCache?.boards || []).find((b) => b.prefix === data.prefix && b.virtual);
      setPageHash(nodesHash('configure', board?.id || data.id));
      return;
    }
    if (nodesUi.addMethod === 'probe') {
      const port = document.querySelector('input[name="addProbePort"]:checked')?.value;
      if (!port) throw new Error('Select a candidate');
      if (logEl) logEl.textContent = 'Probing ' + port + '…';
    const res = await fetch('/api/sensors/probe', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ port })
    });
    const data = await readJsonOrError(res);
      if (!res.ok || !data.ok) throw new Error(data.error || data.systemType || res.statusText);
      showStatus('Probe ok: ' + (data.systemType || 'ok'), 'success');
      await loadSensorsBoards();
      const board = (sensorsPageCache?.boards || []).find((b) =>
        (b.endpoint === port || b.endpoint?.endsWith(port)) ||
        (data.prefix && b.prefix === data.prefix));
      if (board) setPageHash(nodesHash('configure', board.id));
      else setPageHash(nodesHash('view'));
      return;
    }
    if (nodesUi.addMethod === 'bluetooth') {
      const address = document.querySelector('input[name="addBtAddr"]:checked')?.value;
      const prefix = (document.getElementById('addBtPrefix')?.value || '').trim().toUpperCase();
      if (!address) throw new Error('Select a device from scan results');
      const res = await fetch('/api/sensors/bluetooth/pair', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ address, prefix: prefix || undefined })
      });
      const data = await readJsonOrError(res);
      if (!res.ok) throw new Error(data.error || res.statusText);
      showStatus('Paired ' + (data.prefix || address), 'success');
      await loadSensorsBoards();
      const board = (sensorsPageCache?.boards || []).find((b) => b.id === data.id || b.prefix === data.prefix);
      if (board) setPageHash(nodesHash('configure', board.id));
      else setPageHash(nodesHash('view'));
    }
  } catch (err) {
    showStatus(err.message, 'error');
    if (logEl) logEl.textContent = err.message;
  }
}

function validateAddDetails() {
  if (nodesUi.addMethod === 'usb') {
    if (!document.getElementById('addUsbPort')?.value) {
      showStatus('Select a port', 'error');
      return false;
    }
    const prefix = (document.getElementById('addUsbPrefix')?.value || '').trim().toUpperCase();
    if (!/^[A-Z]{1,2}$/.test(prefix)) {
      showStatus('Prefix must be A–Z or AA–ZZ', 'error');
      return false;
    }
  }
  if (nodesUi.addMethod === 'probe') {
    if (!document.querySelector('input[name="addProbePort"]:checked')) {
      showStatus('Select a candidate', 'error');
      return false;
    }
  }
  return true;
}

function boardOutputStateIndex(board) {
  const byIx = {};
  const byPin = {};
  const periodByPin = {};
  (board.outputStates || []).forEach((s) => {
    if (s && s.ix != null) byIx[s.ix] = s;
    const pins = s?.pins || {};
    Object.keys(pins).forEach((p) => {
      const key = String(p).toUpperCase();
      byPin[key] = pins[p];
      if (s.wigwagPeriodMs) periodByPin[key] = s.wigwagPeriodMs;
    });
  });
  return { byIx, byPin, periodByPin };
}

const OUT_LEVEL_CLASSES = [
  'is-out-on', 'is-out-off', 'is-out-blink', 'is-out-moving',
  'is-out-wigwag-a', 'is-out-wigwag-b'
];

function shortPinRoleLabel(typeId, pinIx, paramsOrRoles) {
  let key = null;
  if (Array.isArray(paramsOrRoles)) {
    key = paramsOrRoles[pinIx];
    if (typeId === 'digital_signal' && key) {
      const lab = String(key);
      return lab.length > 8 ? lab.slice(0, 7) + '…' : lab;
    }
    } else {
    const slot = pinSlotsForType(typeId, paramsOrRoles)?.[pinIx];
    key = slot?.key;
  }
  if (key === 'warning') return 'Warn';
  if (key === 'wigwag_left') return 'WW·L';
  if (key === 'wigwag_right') return 'WW·R';
  if (key === 'barrier1') return 'B1';
  if (key === 'barrier2') return 'B2';
  const slot = pinSlotsForType(typeId, paramsOrRoles)?.[pinIx];
  if (slot?.label) {
    const lab = String(slot.label);
    return lab.length > 8 ? lab.slice(0, 7) + '…' : lab;
  }
  if (typeId === 'level_crossing') return 'XC';
  if (typeId === 'digital_signal') return 'O' + (pinIx + 1);
  const n = (outputDisplayName({ type: typeId, name: null }) || typeId || 'Out');
  if (/level\s*crossing/i.test(n)) return 'XC';
  return n.length > 8 ? n.slice(0, 7) + '…' : n;
}

function formatOutLevelDisplay(lvl) {
  if (!lvl || lvl === 'off') return '';
  if (lvl === 'wigwag_a' || lvl === 'wigwag_b') return 'flash';
  if (lvl === 'blinking') return 'blink';
  if (lvl === 'moving') return 'move';
  return String(lvl);
}

/** Compact pin caption: role + state/angle (not the long function title). */
function outputPinAssignText(u) {
  const names = u.pinNames || u.pinRoles;
  const role = shortPinRoleLabel(u.type, u.pinIx, names || u.params);
  const lvl = formatOutLevelDisplay(u.outLevel);
  return lvl ? (role + ' ' + lvl) : role;
}

function applyOutputPinLevelClass(btn, lvl, periodMs) {
  const level = String(lvl || 'off');
  // Avoid restarting CSS animations when the logical level is unchanged
  if (btn.dataset.outLevel === level) {
    if ((level === 'wigwag_a' || level === 'wigwag_b') && periodMs) {
      btn.style.setProperty('--ww-ms', periodMs + 'ms');
    }
    return;
  }
  btn.dataset.outLevel = level;
  OUT_LEVEL_CLASSES.forEach((c) => btn.classList.remove(c));
  if (level === 'on') btn.classList.add('is-out-on');
  else if (level === 'blinking') btn.classList.add('is-out-blink');
  else if (level === 'wigwag_a') btn.classList.add('is-out-wigwag-a');
  else if (level === 'wigwag_b') btn.classList.add('is-out-wigwag-b');
  else if (level.startsWith('→') || level === 'moving') btn.classList.add('is-out-moving');
  else if (/\d/.test(level) && (level.includes('°') || level.includes('%'))) btn.classList.add('is-out-on');
  else if (level !== 'off') btn.classList.add('is-out-on');
  else btn.classList.add('is-out-off');
  if ((level === 'wigwag_a' || level === 'wigwag_b') && periodMs) {
    btn.style.setProperty('--ww-ms', periodMs + 'ms');
  }
}

function configureStructureKey(board) {
  return JSON.stringify({
    id: board.id,
    type: board.boardType,
    prefix: board.prefix,
    pick: nodesUi.pickType || null,
    pickDist: nodesUi.pickDistrictMode || null,
    findDist: nodesUi.highlightDistIx,
    sel: nodesUi.selectedPins || [],
    outs: (board.outputs || []).map((o) => [
      o.type, o.name, o.pins, o.triggers, o.dcc, o.pinNames, o.params?.feedbackVirtual
    ]),
    dists: boardOpticalDistricts(board).map((d) => [d.name, d.ref, d.sensors, d.sensitivityPct]),
    virts: (board.channels || []).filter((c) => c.kind === 'virtual').map((c) => c.id)
  });
}

function virtualPinAssignText(u) {
  const linked = u.linkedName || (u.type ? outputDisplayName(u) : null);
  const st = u.state === 1 ? 'Active' : 'Clear';
  if (linked) {
    const short = String(linked).length > 10 ? String(linked).slice(0, 9) + '…' : String(linked);
    return short + ' · ' + st;
  }
  return 'Virtual · ' + st;
}

function patchConfigureLiveState(board) {
  const diagram = document.getElementById('nodesBoardDiagram');
  const list = document.getElementById('nodesOutputList');
  const usage = boardPinUsage(board);
  if (diagram) {
    diagram.querySelectorAll('button.nodes-pin[data-pin]').forEach((btn) => {
      const label = btn.dataset.pin;
      const u = usage[label] || {};
      if (u.role === 'output') {
        applyOutputPinLevelClass(btn, u.outLevel || 'off', u.wigwagPeriodMs);
        const assign = outputPinAssignText(u);
        const assignEl = btn.querySelector('.nodes-pin-assign');
        if (assignEl && assignEl.textContent !== assign) assignEl.textContent = assign;
        const full = (u.outLabel || outputDisplayName(u)) + (u.outLevel ? ' · ' + u.outLevel : '');
        btn.title = label + ' — ' + full;
      } else if (u.role === 'virtual_in') {
        const assign = virtualPinAssignText(u);
        const assignEl = btn.querySelector('.nodes-pin-assign');
        if (assignEl && assignEl.textContent !== assign) assignEl.textContent = assign;
        btn.classList.toggle('is-active-sense', u.state === 1);
        btn.title = label + ' — ' + (u.id || label) + ' · ' +
          (u.linkedName || 'Virtual feedback') + ' · ' + (u.state === 1 ? 'Active' : 'Clear');
      } else if (u.state === 1) {
        btn.classList.add('is-active-sense');
      } else {
        btn.classList.remove('is-active-sense');
      }
    });
    applyPinHighlights(board);
  }
  if (list) {
    const stateIx = boardOutputStateIndex(board).byIx;
    list.querySelectorAll('.nodes-output-card').forEach((row) => {
      const ix = Number(row.dataset.outIx);
      const st = stateIx[ix];
      const latchId = row.dataset.outLatch || outputLatchId(board.id, ix);
      let badge = row.querySelector('.nodes-out-badge');
      if (!st?.label) {
        if (badge) badge.remove();
      } else {
        if (!badge) {
          const head = row.querySelector('.nodes-output-row-head');
          if (head) {
            badge = document.createElement('span');
            badge.className = 'nodes-out-badge';
            const tools = head.querySelector('.nodes-output-tools');
            head.insertBefore(badge, tools || null);
          }
        }
        if (badge) {
          badge.textContent = st.label;
          badge.classList.toggle('is-animating', !!st.animating);
        }
      }
      const latched = nodesUi.lastAccessoryByOutput[latchId] || null;
      row.querySelectorAll('button.nodes-func-state').forEach((btn) => {
        btn.classList.toggle('is-active', !!latched && btn.getAttribute('data-acc-key') === latched);
      });
      row.classList.toggle('is-finding', nodesUi.highlightOutIx === ix);
    });
  }
}

function boardPinUsage(board) {
  const map = {};
  const outStates = boardOutputStateIndex(board);
  const virtualLinks = {};
  (board.pins || []).forEach((p) => {
    if (!p || !p.label) return;
    const role = p.role || 'free';
    // Optical/ref occupancy comes from saved districts only (no phantom defaults)
    if (role === 'optical' || role === 'ref') return;
    map[p.label.toUpperCase()] = { role, id: p.id, source: 'pin' };
  });
  (board.outputs || []).forEach((o, ix) => {
    const firstDcc = Array.isArray(o.triggers) && o.triggers[0] ? o.triggers[0].dcc : o.dcc;
    const st = outStates.byIx[ix];
    (o.pins || []).forEach((pin, pinIx) => {
      const label = String(pin).toUpperCase();
      map[label] = {
        role: 'output',
        outputIx: ix,
        type: o.type,
        dcc: firstDcc,
        name: o.name,
        pinIx,
        pinRoles: o.pinRoles || null,
        pinNames: o.pinNames || null,
        params: o.params || null,
        source: 'output',
        outLevel: outStates.byPin[label] || 'off',
        outLabel: st?.label || null,
        wigwagPeriodMs: outStates.periodByPin[label] || st?.wigwagPeriodMs || null
      };
    });
    if (o.type === 'level_crossing') {
      const v = normalizeVirtualLabelUi(o.params?.feedbackVirtual);
      if (v) {
        virtualLinks[v] = {
          outputIx: ix,
          name: o.name,
          type: o.type,
          outLabel: st?.label || null
        };
      }
    }
  });
  boardOpticalDistricts(board).forEach((d, dix) => {
    if (d.ref) {
      const prev = map[d.ref];
      if (prev?.role !== 'output') {
        map[d.ref] = {
          role: 'ref',
          districtIx: dix,
          districtName: d.name,
          source: 'district'
        };
      }
    }
    (d.sensors || []).forEach((s) => {
      const prev = map[s];
      if (prev?.role === 'output') return;
      map[s] = {
        role: 'optical',
        districtIx: dix,
        districtName: d.name,
        state: prev?.state,
        id: prev?.id,
        source: 'district'
      };
    });
  });
  (board.channels || []).forEach((ch) => {
    const label = (ch.label || '').toUpperCase();
    if (!label) return;
    if (map[label]?.role === 'output') return;
    if (map[label]?.role === 'ref') return;
    if (ch.kind === 'virtual') {
      const link = virtualLinks[label] || {};
      map[label] = {
        role: 'virtual_in',
        state: ch.state,
        id: ch.id,
        source: 'channel',
        linkedOutputIx: link.outputIx != null ? link.outputIx : null,
        linkedName: link.name ? outputDisplayName({ name: link.name, type: link.type }) : null,
        type: link.type || 'virtual',
        name: link.name || null,
        outLabel: link.outLabel || null
      };
      return;
    }
    const prev = map[label];
    map[label] = {
      role: ch.kind === 'digital' ? 'digital_in' : 'optical',
      state: ch.state,
      id: ch.id,
      districtIx: prev?.districtIx,
      districtName: prev?.districtName,
      source: prev?.source === 'district' ? 'district' : 'channel'
    };
  });
  return map;
}

/** Prefer server mirrored state for view-button latch. */
function syncViewLatchFromOutputStates(board) {
  if (!board?.id || !Array.isArray(board.outputStates)) return;
  (board.outputs || []).forEach((o, ix) => {
    const st = (board.outputStates || []).find((s) => s.ix === ix);
    if (!st) return;
    const trig = pickLatchTrigger(o, st);
    if (!trig) return;
    const dcc = Number(trig.dcc) || 0;
    const closed = triggerDirectionClosed(trig.direction);
    nodesUi.lastAccessoryByOutput[outputLatchId(board.id, ix)] =
      dcc + ':' + (closed ? 'on' : 'off');
  });
}

function sortedIntKey(arr) {
  return (Array.isArray(arr) ? arr : []).map(Number).filter((n) => Number.isFinite(n)).sort((a, b) => a - b).join(',');
}

function pickLatchTrigger(o, st) {
  const triggers = Array.isArray(o.triggers) ? o.triggers : [];
  if (!triggers.length) return null;
  if (o.type === 'digital_signal') {
    const state = String(st.state || '');
    if (!state || state === 'off') return null;
    let hit = triggers.find((t) => t.state && String(t.state) === state);
    if (hit) return hit;
    if (st.label) {
      hit = triggers.find((t) => t.label && String(t.label) === String(st.label));
      if (hit) return hit;
    }
    const onKey = sortedIntKey(st.on);
    const flashKey = sortedIntKey(st.flashing);
    hit = triggers.find((t) => sortedIntKey(t.on) === onKey && sortedIntKey(t.flashing) === flashKey);
    return hit || null;
  }
  if (o.type === 'servo' && st.deg != null) {
    const hit = triggers.find((t) => Number(t.deg) === Number(st.deg));
    if (hit) return hit;
  }
  if (o.type === 'fade' && st.brightness != null) {
    const hit = triggers.find((t) => Number(t.brightness) === Number(st.brightness));
    if (hit) return hit;
  }
  const state = String(st.state || '').toLowerCase();
  const phase = String(st.phase || '').toLowerCase();
  let preferOn = true;
  if (o.type === 'level_crossing') {
    preferOn = state === 'open' || state === 'opening'
      || phase === 'open' || phase === 'clearing' || phase === 'barriers_opening';
  } else if (state === 'off') {
    preferOn = false;
  }
  for (const t of triggers) {
    if (triggerDirectionClosed(t.direction) === preferOn) return t;
  }
  return triggers[0];
}

function boardColumns(profile) {
  if (!profile?.pins?.length) return { left: [], right: [], extra: [], virtual: [] };
  const isMega = (profile.id || '').indexOf('mega') >= 0;
  const byLabel = {};
  profile.pins.forEach((p) => { byLabel[p.silkLabel] = p; });
  if (!isMega) {
    const left = [];
    for (let d = 0; d <= 13; d++) if (byLabel['D' + d]) left.push(byLabel['D' + d]);
    const right = [];
    for (let a = 0; a <= 7; a++) if (byLabel['A' + a]) right.push(byLabel['A' + a]);
    return { left, right, extra: [], virtual: [] };
  }
  const left = [];
  for (let d = 2; d <= 13; d++) if (byLabel['D' + d]) left.push(byLabel['D' + d]);
  const right = [];
  for (let a = 0; a <= 15; a++) if (byLabel['A' + a]) right.push(byLabel['A' + a]);
  const extra = [];
  for (let d = 22; d <= 37; d++) if (byLabel['D' + d]) extra.push(byLabel['D' + d]);
  return { left, right, extra, virtual: [] };
}

/** Synthetic pin defs for allocated virtual feedback channels on a board. */
function virtualPinsForBoard(board) {
  return (board.channels || [])
    .filter((c) => c.kind === 'virtual' && c.label)
    .map((c) => ({
      silkLabel: String(c.label).toUpperCase(),
      capabilities: ['virtual_in'],
      hardwareId: c.id
    }))
    .sort((a, b) => {
      const na = Number(String(a.silkLabel).replace(/\D/g, '')) || 0;
      const nb = Number(String(b.silkLabel).replace(/\D/g, '')) || 0;
      return na - nb;
    });
}

function renderNodesConfigure() {
  const board = findBoardById(nodesUi.configureId);
  const meta = document.getElementById('nodesConfigMeta');
  const diagram = document.getElementById('nodesBoardDiagram');
  const list = document.getElementById('nodesOutputList');
  if (!board) {
    if (meta) meta.innerHTML = '<p class="wifi-hint">Board not found. <a href="#sensors">Back to list</a></p>';
    if (diagram) diagram.innerHTML = '';
    nodesUi._configureStructureKey = null;
    return;
  }
  const profile = findProfile(board.boardType) || { pins: [], id: board.boardType };
  if (meta) {
    meta.innerHTML = '<span class="wifi-mode-badge">' + (board.virtual ? 'Virtual' : (board.transport || 'usb')) +
      '</span> <strong>' + (board.boardType || '') + '</strong> · <code>' + (board.endpoint || '') +
      '</code> · ' + (board.status || '');
  }

  const structKey = configureStructureKey(board);
  // Keep type/shape controls mounted while polling — remounting wiped the selects.
  const canPatch = diagram && diagram.querySelector('.nodes-pcb')
    && nodesUi._configureStructureKey === structKey;
  if (canPatch) {
    patchConfigureLiveState(board);
    return;
  }
  nodesUi._configureStructureKey = structKey;
  renderTypePicker();

  const usage = boardPinUsage(board);
  const cols = boardColumns(profile);
  const virtPins = virtualPinsForBoard(board);
  if (diagram) {
    diagram.innerHTML = '';
    const isMega = (profile.id || '').indexOf('mega') >= 0;
    diagram.className = 'nodes-board-diagram' + (isMega ? ' is-mega' : '') +
      (virtPins.length ? ' has-virtual' : '');
    const shell = document.createElement('div');
    shell.className = 'nodes-pcb';
    shell.innerHTML = '<div class="nodes-pcb-label">' + (board.prefix || '?') + '</div>' +
      '<div class="nodes-pcb-usb" aria-hidden="true">USB</div>';
    const mid = document.createElement('div');
    mid.className = 'nodes-pcb-pins';
    mid.appendChild(renderPinColumn(cols.left, usage, 'left'));
    const center = document.createElement('div');
    center.className = 'nodes-pcb-center';
    const body = document.createElement('div');
    body.className = 'nodes-pcb-body';
    body.innerHTML = '<div class="nodes-pcb-chip"></div>';
    center.appendChild(body);
    if (cols.extra.length) {
      const bank = document.createElement('div');
      bank.className = 'nodes-pcb-mid-bank';
      bank.innerHTML = '<div class="nodes-pcb-bank-label">D22–D37</div>';
      bank.appendChild(renderPinColumn(cols.extra, usage, 'mid'));
      center.appendChild(bank);
    }
    if (virtPins.length) {
      const vbank = document.createElement('div');
      vbank.className = 'nodes-pcb-mid-bank nodes-pcb-virtual-bank';
      vbank.innerHTML = '<div class="nodes-pcb-bank-label">Virtual Vn</div>';
      vbank.appendChild(renderPinColumn(virtPins, usage, 'virtual'));
      center.appendChild(vbank);
    }
    mid.appendChild(center);
    mid.appendChild(renderPinColumn(cols.right, usage, 'right'));
    shell.appendChild(mid);
    diagram.appendChild(shell);
  }

  if (list) {
    const outs = board.outputs || [];
    const stateIx = boardOutputStateIndex(board).byIx;
    if (!outs.length) {
      list.innerHTML = '<p class="wifi-hint">None yet — choose an output type, then pick pins on the board.</p>';
    } else {
      list.innerHTML = '';
      outs.forEach((o, ix) => {
        list.appendChild(renderAssignedOutputCard(board, o, ix, stateIx[ix]));
      });
      bindViewOutputControls(list);
    }
    applyPinHighlights(board);
  }

  renderDistrictList(board);

  const simPanel = document.getElementById('nodesSimPanel');
  const simBtns = document.getElementById('nodesSimButtons');
  if (simPanel && simBtns) {
    if (board.virtual) {
      simPanel.hidden = false;
      const allCh = board.channels || [];
      const simList = allCh.filter((c) => c.kind !== 'virtual').slice(0, 10)
        .concat(allCh.filter((c) => c.kind === 'virtual'));
      // Keep sim buttons stable across polls — only rebuild when ids change
      const simKey = simList.map((c) => c.id).join('|');
      if (simBtns.dataset.simKey !== simKey) {
        simBtns.dataset.simKey = simKey;
        simBtns.innerHTML = '';
        simList.forEach((ch) => {
          const tog = document.createElement('button');
          tog.type = 'button';
          tog.className = 'btn btn-secondary btn-small' + (ch.state === 1 ? ' is-on' : '');
          tog.dataset.hwId = ch.id;
          tog.textContent = ch.label || ch.id;
          tog.onclick = () => simulateShimChannel(board.id, ch.id, ch.state === 1 ? 0 : 1);
          simBtns.appendChild(tog);
        });
      } else {
        simBtns.querySelectorAll('button').forEach((tog) => {
          const ch = simList.find((c) => c.id === tog.dataset.hwId);
          if (ch) tog.classList.toggle('is-on', ch.state === 1);
        });
      }
    } else {
      simPanel.hidden = true;
    }
  }
}

function renderPinColumn(pins, usage, side) {
  const col = document.createElement('div');
  col.className = 'nodes-pin-col side-' + side;
  const picking = !!nodesUi.pickType;
  pins.forEach((pin) => {
    const label = pin.silkLabel;
    const u = usage[label] || { role: 'free' };
    const caps = pin.capabilities || [];
    let role = u.role || 'free';
    // Ref is just an analogue pin used by a district — same visual as other optical pins
    if (role === 'ref') role = 'optical';
    // Unassigned analogue-capable pins show as Analogue (not Free)
    if (role === 'free' && caps.includes('optical')) role = 'optical';
    if (pinIsReserved(pin) && role !== 'output' && role !== 'virtual_in' && role !== 'optical') {
      role = 'locked';
    }
    const isVirtual = role === 'virtual_in';
    const selectable = !isVirtual && picking && pinSelectableForPick(pin, u, nodesUi.pickType);
    const unavailable = picking && !selectable && !isVirtual;
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'nodes-pin role-' + role;
    if (unavailable) btn.classList.add('is-unavailable');
    if (selectable) btn.classList.add('is-available');
    if (nodesUi.selectedPins.includes(label)) btn.classList.add('is-selected');
    if (u.state === 1) btn.classList.add('is-active-sense');
    if (role === 'output') {
      applyOutputPinLevelClass(btn, u.outLevel || 'off', u.wigwagPeriodMs);
    }
    btn.dataset.pin = label;
    let assign;
    if (unavailable) {
      assign = 'Unavailable';
    } else if (selectable && u.role !== 'output') {
      assign = 'Available';
    } else if (u.role === 'output') {
      assign = outputPinAssignText(u);
    } else if (role === 'virtual_in') {
      assign = virtualPinAssignText(u);
    } else if (role === 'optical') {
      assign = u.districtName
        ? (String(u.districtName).length > 12
          ? String(u.districtName).slice(0, 11) + '…'
          : String(u.districtName))
        : 'Analogue';
    } else if (role === 'digital_in') {
      assign = 'Digital in';
    } else if (role === 'locked') {
      assign = 'Reserved';
    } else {
      assign = 'Free';
    }
    btn.innerHTML = '<span class="nodes-pin-label">' + label + '</span><span class="nodes-pin-assign">' + assign + '</span>';
    if (u.role === 'output') {
      btn.title = label + ' — ' + (u.outLabel || outputDisplayName(u)) +
        (u.outLevel ? ' · ' + u.outLevel : '');
    } else if (role === 'virtual_in') {
      btn.title = label + ' — ' + (u.id || label) + ' · ' +
        (u.linkedName || 'Virtual feedback') + ' · ' + (u.state === 1 ? 'Active' : 'Clear');
    } else {
      btn.title = label + ' — ' + assign;
    }
    // While picking: only valid pins. Idle: outputs, districts, linked virtual feedback.
    const idleClickable = role === 'output'
      || (role === 'virtual_in' && u.linkedOutputIx != null)
      || ((u.role === 'ref' || u.role === 'optical') && u.districtIx != null);
    btn.disabled = picking ? (unavailable || isVirtual) : !idleClickable;
    btn.onclick = () => onBoardPinClick(label, u, pin);
    col.appendChild(btn);
  });
  return col;
}

function onBoardPinClick(label, usage, pin) {
  const board = findBoardById(nodesUi.configureId);
  if (!board) return;
  if (usage && usage.role === 'output' && usage.outputIx != null && !nodesUi.pickType) {
    openOutputModal(board, usage.outputIx);
    return;
  }
  if (usage && usage.role === 'virtual_in' && usage.linkedOutputIx != null && !nodesUi.pickType) {
    openOutputModal(board, usage.linkedOutputIx);
    return;
  }
  if (!nodesUi.pickType && usage && (usage.role === 'ref' || usage.role === 'optical')
      && usage.districtIx != null) {
    openDistrictModal(board, usage.districtIx);
    return;
  }
  if (!nodesUi.pickType) return;
  if (!pinSelectableForPick(pin || { silkLabel: label, capabilities: [] }, usage, nodesUi.pickType)) {
    return;
  }
  if (nodesUi.pickType === 'optical_district') {
    if (nodesUi.pickDistrictMode === 'ref') {
      nodesUi.selectedPins = [label];
      applyDistrictPinPick(board);
      return;
    }
    const ix = nodesUi.selectedPins.indexOf(label);
    if (ix >= 0) nodesUi.selectedPins.splice(ix, 1);
    else nodesUi.selectedPins.push(label);
    if (nodesUi._modalDistrict && !nodesUi.districtCreating) {
      nodesUi._modalDistrict.sensors = nodesUi.selectedPins.filter(
        (s) => s && s !== nodesUi._modalDistrict.ref
      );
    }
    nodesUi._configureStructureKey = null;
    renderNodesConfigure();
    return;
  }
  const need = pinsNeededForType(nodesUi.pickType);
  const ix = nodesUi.selectedPins.indexOf(label);
  if (ix >= 0) {
    nodesUi.selectedPins.splice(ix, 1);
  } else {
    if (nodesUi.pickType !== 'digital_signal' && nodesUi.selectedPins.length >= need) {
      nodesUi.selectedPins.shift();
    }
    if (nodesUi.pickType === 'digital_signal' && nodesUi.selectedPins.length >= 8) {
      showStatus('Maximum 8 outputs per signal', 'error');
      return;
    }
    nodesUi.selectedPins.push(label);
  }
  renderNodesConfigure();
  if (nodesUi.pickType !== 'digital_signal' && nodesUi.selectedPins.length >= need) {
    openNewOutputModal(board, nodesUi.pickType, nodesUi.selectedPins.slice());
  }
}

function nextDccSuggestion(board) {
  const used = new Set();
  (board.outputs || []).forEach((o) => {
    if (o.dcc != null) used.add(Number(o.dcc));
    (o.triggers || []).forEach((t) => { if (t.dcc != null) used.add(Number(t.dcc)); });
  });
  let n = 1;
  while (used.has(n)) n++;
  return n;
}

function seedTriggersForType(board, type) {
  const meta = outputTypeMeta(type);
  const base = nextDccSuggestion(board);
  if (type === 'digital_signal') {
    return [multiOutDefaultTrigger(base, 1)];
  }
  const defaults = (meta?.defaultTriggers || []).map((t) => Object.assign({}, t));
  if (!defaults.length) {
    return [{ dcc: base, direction: 'Forward' }];
  }
  return defaults.map((t) => {
    const copy = Object.assign({}, t);
    copy.dcc = base;
    return copy;
  });
}

function openNewOutputModal(board, type, pins) {
  nodesUi.editingOutputIx = null;
  nodesUi.editOriginalDcc = null;
  setOutputTypeDisplay(type);
  const nameEl = document.getElementById('nodeOutputName');
  if (nameEl) nameEl.value = suggestOutputName(board, type);
  nodesUi._modalPins = pins;
  nodesUi._modalPinNames = signalPinNames(pins, []);
  nodesUi._modalBoardId = board.id;
  nodeModalTriggers = seedTriggersForType(board, type);
  renderNodeOutputParams({ pinNames: nodesUi._modalPinNames });
  renderNodeTriggersEditor();
  const names = nodesUi._modalPinNames;
  document.getElementById('nodesModalTitle').textContent = 'New ' + outputLabel(type);
  document.getElementById('nodesModalPins').textContent =
    pins.map((p, i) => (names[i] || 'Output') + ': ' + p).join(' · ');
  document.getElementById('btnNodeDeleteOutput').hidden = true;
  document.getElementById('nodesConfigModal').hidden = false;
  nameEl?.focus();
  nameEl?.select();
}

function openOutputModal(board, outputIx) {
  const o = (board.outputs || [])[outputIx];
  if (!o) return;
  nodesUi.editingOutputIx = outputIx;
  nodesUi.editOriginalDcc = null;
  nodesUi.pickType = null;
  nodesUi.selectedPins = [];
  setOutputTypeDisplay(o.type);
  const nameEl = document.getElementById('nodeOutputName');
  if (nameEl) nameEl.value = (o.name || '').trim() || outputDisplayName(o);
  const meta = outputTypeMeta(o.type);
  nodeModalTriggers = normalizeOutputTriggers(o, meta);
  nodesUi._modalPins = (o.pins || []).slice();
  nodesUi._modalPinNames = signalPinNames(o.pins || [], o.pinNames || o.params?.pinNames || o.pinRoles || []);
  nodesUi._modalBoardId = board.id;
  const editParams = Object.assign({}, o.params || {}, { pinNames: nodesUi._modalPinNames });
  renderNodeOutputParams(editParams);
  renderNodeTriggersEditor();
  document.getElementById('nodesModalTitle').textContent = 'Edit ' + outputDisplayName(o);
  document.getElementById('nodesModalPins').textContent =
    (o.pins || []).map((p, i) => (nodesUi._modalPinNames[i] || 'Output') + ': ' + p).join(' · ') || 'No pins';
  document.getElementById('btnNodeDeleteOutput').hidden = false;
  document.getElementById('nodesConfigModal').hidden = false;
  renderNodesConfigure();
}

function closeNodesModal() {
  const modal = document.getElementById('nodesConfigModal');
  if (modal) modal.hidden = true;
  setOutputTypeDisplay('');
  nodesUi.selectedPins = [];
  nodesUi.pickType = null;
  nodesUi.editingOutputIx = null;
  nodesUi._modalPins = null;
  nodesUi._modalPinNames = null;
  nodeModalTriggers = [];
}

async function persistBoardConfig(boardId, outputs, pins, opticalDistricts) {
  const body = {
    outputs,
    pins: pins || [],
    opticalDistricts: opticalDistricts || []
  };
  const res = await fetch('/api/sensors/' + encodeURIComponent(boardId) + '/config', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
  const data = await readJsonOrError(res);
  if (!res.ok) throw new Error(data.error || res.statusText);
  return data;
}

function syncPinsForOutputs(board, outputs, opticalDistricts) {
  return syncPinsForBoard(board, outputs, opticalDistricts);
}

function syncPinsForBoard(board, outputs, opticalDistricts) {
  const profile = findProfile(board.boardType);
  const outPins = new Set();
  (outputs || []).forEach((o) => (o.pins || []).forEach((p) => outPins.add(String(p).toUpperCase())));
  // Only saved districts — empty means no optical/ref roles (create from scratch)
  const districts = Array.isArray(opticalDistricts)
    ? opticalDistricts
    : boardOpticalDistricts(board);
  const refPins = new Set();
  const opticalPins = new Set();
  districts.forEach((d) => {
    if (d.ref) refPins.add(String(d.ref).toUpperCase());
    (d.sensors || []).forEach((s) => opticalPins.add(String(s).toUpperCase()));
  });
  const defaultDigital = new Set(profile?.defaultDigitalLabels || []);
  const pins = [];
  (profile?.pins || []).forEach((p) => {
    const label = p.silkLabel;
    if (outPins.has(label)) {
      pins.push({ label, role: 'output' });
    } else if (refPins.has(label)) {
      pins.push({ label, role: 'ref' });
    } else if (opticalPins.has(label)) {
      pins.push({ label, role: 'optical' });
    } else if (defaultDigital.has(label)) {
      pins.push({ label, role: 'digital_in' });
    }
  });
  return pins;
}

async function saveBoardDistricts(board, districts) {
  const outputs = Array.isArray(board.outputs) ? board.outputs : [];
  const normalized = (districts || []).map((d) => normalizeDistrict(d));
  await persistBoardConfig(
    board.id,
    outputs,
    syncPinsForBoard(board, outputs, normalized),
    normalized
  );
}

async function saveNodeOutputCfg() {
  const id = nodesUi._modalBoardId || nodesUi.configureId;
  const board = findBoardById(id);
  if (!board) {
    showStatus('Board not found', 'error');
    return;
  }
  const type = document.getElementById('nodeOutputType')?.value || 'servo';
  const meta = outputTypeMeta(type);
  const name = (document.getElementById('nodeOutputName')?.value || '').trim() || suggestOutputName(board, type);
  const pins = (nodesUi._modalPins || []).map((p) => String(p).toUpperCase());
  const triggers = collectNodeTriggers();
  if (!pins.length) {
    showStatus('Pins required', 'error');
    return;
  }
  if (!triggers.length) {
    showStatus('At least one DCC trigger is required', 'error');
    return;
  }
  for (const t of triggers) {
    if (!t.dcc || t.dcc < 1) {
      showStatus('Each trigger needs a DCC number', 'error');
      return;
    }
    if (!t.direction) {
      showStatus('Each trigger needs a direction (Forward/Reverse)', 'error');
      return;
    }
    if (meta?.triggerMode === 'positions') {
      const vf = positionsValueField(meta);
      if (vf) {
        const v = Number(t[vf.id]);
        const min = vf.minimum != null ? vf.minimum : 0;
        const max = vf.maximum != null ? vf.maximum : 100;
        if (!Number.isFinite(v) || v < min || v > max) {
          showStatus('Each level needs ' + (vf.title || vf.id) + ' ' + min + '–' + max, 'error');
          return;
        }
      }
    }
  }
  const minT = meta?.minTriggers || 1;
  const maxT = meta?.maxTriggers || 16;
  if (triggers.length < minT || triggers.length > maxT) {
    showStatus('This function needs between ' + minT + ' and ' + maxT + ' triggers', 'error');
    return;
  }
  const params = collectNodeOutputParams();
  // Drop legacy keys
  delete params.posA_deg;
  delete params.posB_deg;
  delete params.brightnessOn;
  delete params.brightnessOff;
  delete params.lightsOnMs;
  delete params.lightsOffMs;
  delete params.activeLevel;
  if (type === 'digital_signal') {
    delete params.kind;
    delete params.aspects;
    delete params.routes;
    delete params.aspectColours;
    delete params.pinNames;
    if (params.flashMs == null) params.flashMs = 500;
  }
  if (type === 'level_crossing') {
    let v = normalizeVirtualLabelUi(params.feedbackVirtual);
    if (!v) v = nextFreeVirtualLabel(board, nodesUi.editingOutputIx);
    if (!v) {
      showStatus('No free virtual feedback channels (V1–V12) on this board', 'error');
      return;
    }
    params.feedbackVirtual = v;
    params.feedbackActiveState = params.feedbackActiveState === 0 ? 0 : 1;
  }
  const output = { name, type, pins, params, triggers };
  const slots = pinSlotsForType(type, params);
  if (type === 'digital_signal') {
    output.pinNames = collectSignalPinNames();
    output.pinRoles = output.pinNames.slice();
  } else if (slots?.length) {
    output.pinRoles = slots.map((s) => s.key);
  }
  const outputs = Array.isArray(board.outputs) ? board.outputs.map((o) => Object.assign({}, o)) : [];
  if (nodesUi.editingOutputIx != null) {
    outputs[nodesUi.editingOutputIx] = output;
  } else {
    outputs.push(output);
  }
  try {
    const districts = boardOpticalDistricts(board);
    await persistBoardConfig(id, outputs, syncPinsForBoard(board, outputs, districts), districts);
    showStatus('Saved ' + name, 'success');
    closeNodesModal();
    await loadSensorsBoards();
  } catch (err) {
    showStatus('Save failed: ' + err.message, 'error');
  }
}

async function deleteNodeOutputCfg() {
  const id = nodesUi._modalBoardId || nodesUi.configureId;
  const board = findBoardById(id);
  if (!board || nodesUi.editingOutputIx == null) return;
  const outputs = (board.outputs || []).slice();
  outputs.splice(nodesUi.editingOutputIx, 1);
  try {
    const districts = boardOpticalDistricts(board);
    await persistBoardConfig(id, outputs, syncPinsForBoard(board, outputs, districts), districts);
    showStatus('Deleted function', 'success');
    closeNodesModal();
    await loadSensorsBoards();
  } catch (err) {
    showStatus('Delete failed: ' + err.message, 'error');
  }
}

async function sendNodeTestCommand(command) {
  const id = nodesUi._modalBoardId || nodesUi.configureId;
  if (!id) {
    showStatus('No board selected', 'error');
    return;
  }
  try {
    const res = await fetch('/api/sensors/' + encodeURIComponent(id) + '/test', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ command })
    });
    const data = await readJsonOrError(res);
    if (!res.ok) throw new Error(data.error || res.statusText);
    if (data.reply) return data;
    showStatus('Test sent: ' + data.sent, 'success');
    return data;
  } catch (err) {
    showStatus('Test failed: ' + err.message, 'error');
    return null;
  }
}

function stopDistrictCalibrate() {
  if (nodesUi.calibrate?.timer) {
    clearInterval(nodesUi.calibrate.timer);
  }
  nodesUi.calibrate = null;
  refreshDistrictCalPanel();
}

function districtPinSummary(d) {
  if (!d) return 'No pins';
  const bits = [];
  if (d.ref) bits.push('Ref ' + d.ref);
  if ((d.sensors || []).length) bits.push('Sensors ' + d.sensors.join(', '));
  return bits.join(' · ') || 'No pins';
}

function syncDistrictModalSummary() {
  const d = nodesUi._modalDistrict;
  const pinsEl = document.getElementById('nodesDistrictModalPins');
  const sumEl = document.getElementById('nodesDistrictPinSummary');
  const text = districtPinSummary(d);
  if (pinsEl) pinsEl.textContent = text;
  if (sumEl) sumEl.textContent = text;
}

function openDistrictModal(board, districtIx) {
  const districts = boardOpticalDistricts(board);
  const d = districts[districtIx];
  if (!d) return;
  stopDistrictCalibrate();
  nodesUi.editingDistrictIx = districtIx;
  nodesUi._modalDistrict = normalizeDistrict(d);
  nodesUi._modalBoardId = board.id;
  nodesUi.pickType = null;
  nodesUi.pickDistrictMode = null;
  nodesUi.districtCreating = false;
  nodesUi.selectedPins = [];
  const nameEl = document.getElementById('nodeDistrictName');
  if (nameEl) nameEl.value = d.name || '';
  const pctEl = document.getElementById('nodeDistrictPct');
  if (pctEl) pctEl.value = String(clampSensitivityPct(d.sensitivityPct));
  document.getElementById('nodesDistrictModalTitle').textContent = 'Edit ' + (d.name || 'district');
  document.getElementById('btnNodeDeleteDistrict').hidden = false;
  syncDistrictModalSummary();
  refreshDistrictCalPanel();
  document.getElementById('nodesDistrictModal').hidden = false;
  nameEl?.focus();
  nameEl?.select();
}

function closeDistrictModal() {
  stopDistrictCalibrate();
  const modal = document.getElementById('nodesDistrictModal');
  if (modal) modal.hidden = true;
  nodesUi.editingDistrictIx = null;
  nodesUi._modalDistrict = null;
  nodesUi.districtCreating = false;
  if (nodesUi.pickType === 'optical_district') {
    nodesUi.pickType = null;
    nodesUi.pickDistrictMode = null;
    nodesUi.selectedPins = [];
    nodesUi._configureStructureKey = null;
    renderNodesConfigure();
  }
}

function beginDistrictPinPick(mode) {
  const board = findBoardById(nodesUi.configureId);
  const d = nodesUi._modalDistrict;
  if (!board || !d) return;
  // Re-pick from config modal (keep draft; not a create flow)
  document.getElementById('nodesDistrictModal').hidden = true;
  nodesUi.districtCreating = false;
  nodesUi.pickType = 'optical_district';
  nodesUi.pickDistrictMode = mode;
  nodesUi.selectedPins = mode === 'ref'
    ? (d.ref ? [d.ref] : [])
    : (d.sensors || []).slice();
  nodesUi._configureStructureKey = null;
  renderNodesConfigure();
}

function applyDistrictPinPick(board) {
  if (nodesUi.pickDistrictMode === 'ref') {
    const ref = nodesUi.selectedPins[0] || '';
    if (nodesUi.districtCreating) {
      // Create flow: reference chosen → next pick sensors
      nodesUi._modalDistrict = {
        name: suggestDistrictName(board),
        ref,
        sensors: [],
        sensitivityPct: 15
      };
      nodesUi.pickDistrictMode = 'sensors';
      nodesUi.selectedPins = [];
      nodesUi._configureStructureKey = null;
      renderNodesConfigure();
      return;
    }
    if (nodesUi._modalDistrict) {
      nodesUi._modalDistrict.ref = ref;
      nodesUi._modalDistrict.sensors = (nodesUi._modalDistrict.sensors || []).filter((s) => s !== ref);
    }
    finishDistrictPinPick();
    return;
  }
  // Sensors multi-pick — stay until Done
  if (nodesUi._modalDistrict) {
    nodesUi._modalDistrict.sensors = nodesUi.selectedPins.filter(
      (s) => s && s !== nodesUi._modalDistrict.ref
    );
  }
  nodesUi._configureStructureKey = null;
  renderNodesConfigure();
}

/** Finish sensor multi-pick (create → open config modal; edit → return to modal). */
function finishDistrictSensorPick() {
  const board = findBoardById(nodesUi.configureId);
  if (!board || nodesUi.selectedPins.length < 1) return;
  const ref = nodesUi._modalDistrict?.ref || '';
  const sensors = nodesUi.selectedPins.filter((s) => s && s !== ref);
  if (!sensors.length) {
    showStatus('Pick at least one sensor pin', 'error');
    return;
  }
  if (nodesUi.districtCreating) {
    const draft = normalizeDistrict({
      name: nodesUi._modalDistrict?.name || suggestDistrictName(board),
      ref,
      sensors,
      sensitivityPct: nodesUi._modalDistrict?.sensitivityPct || 15
    });
    nodesUi.pickType = null;
    nodesUi.pickDistrictMode = null;
    nodesUi.selectedPins = [];
    nodesUi.districtCreating = false;
    nodesUi._configureStructureKey = null;
    renderNodesConfigure();
    openNewDistrictModal(board, draft);
    return;
  }
  if (nodesUi._modalDistrict) {
    nodesUi._modalDistrict.sensors = sensors;
  }
  finishDistrictPinPick();
}

function finishDistrictPinPick() {
  nodesUi.pickType = null;
  nodesUi.pickDistrictMode = null;
  nodesUi.selectedPins = [];
  nodesUi.districtCreating = false;
  nodesUi._configureStructureKey = null;
  renderNodesConfigure();
  if (nodesUi._modalDistrict) {
    syncDistrictModalSummary();
    document.getElementById('nodesDistrictModal').hidden = false;
  }
}

/** Open config modal for a newly picked district (not yet saved). */
function openNewDistrictModal(board, draft) {
  stopDistrictCalibrate();
  nodesUi.editingDistrictIx = null;
  nodesUi._modalDistrict = normalizeDistrict(draft);
  nodesUi._modalBoardId = board.id;
  nodesUi.pickType = null;
  nodesUi.pickDistrictMode = null;
  nodesUi.districtCreating = false;
  nodesUi.selectedPins = [];
  const nameEl = document.getElementById('nodeDistrictName');
  if (nameEl) nameEl.value = nodesUi._modalDistrict.name || '';
  const pctEl = document.getElementById('nodeDistrictPct');
  if (pctEl) pctEl.value = String(clampSensitivityPct(nodesUi._modalDistrict.sensitivityPct));
  document.getElementById('nodesDistrictModalTitle').textContent = 'Configure sensor district';
  document.getElementById('btnNodeDeleteDistrict').hidden = true;
  syncDistrictModalSummary();
  refreshDistrictCalPanel();
  document.getElementById('nodesDistrictModal').hidden = false;
  nameEl?.focus();
  nameEl?.select();
}

async function saveDistrictModal() {
  const board = findBoardById(nodesUi._modalBoardId || nodesUi.configureId);
  if (!board || !nodesUi._modalDistrict) return;
  const d = normalizeDistrict(nodesUi._modalDistrict);
  d.name = (document.getElementById('nodeDistrictName')?.value || '').trim() || d.name;
  d.sensitivityPct = clampSensitivityPct(document.getElementById('nodeDistrictPct')?.value);
  if (!d.ref) {
    showStatus('Choose a Reference pin', 'error');
    return;
  }
  if (!(d.sensors || []).length) {
    showStatus('Pick at least one sensor pin', 'error');
    return;
  }
  const districts = boardOpticalDistricts(board).slice();
  if (nodesUi.editingDistrictIx == null) {
    districts.push(d);
  } else {
    districts[nodesUi.editingDistrictIx] = d;
  }
  try {
    stopDistrictCalibrate();
    await saveBoardDistricts(board, districts);
    showStatus('Saved district ' + d.name, 'success');
    closeDistrictModal();
    await loadSensorsBoards();
  } catch (err) {
    showStatus('Save failed: ' + err.message, 'error');
  }
}

async function deleteDistrictModal() {
  const board = findBoardById(nodesUi._modalBoardId || nodesUi.configureId);
  if (!board || nodesUi.editingDistrictIx == null) return;
  const districts = boardOpticalDistricts(board).slice();
  if (!districts.length) {
    closeDistrictModal();
    return;
  }
  districts.splice(nodesUi.editingDistrictIx, 1);
  try {
    stopDistrictCalibrate();
    await saveBoardDistricts(board, districts);
    showStatus(
      districts.length ? 'District deleted' : 'All sensor districts removed',
      'success'
    );
    closeDistrictModal();
    await loadSensorsBoards();
  } catch (err) {
    showStatus('Delete failed: ' + err.message, 'error');
  }
}

function refreshDistrictCalPanel() {
  const panel = document.getElementById('nodesDistrictCalPanel');
  if (!panel) return;
  const cal = nodesUi.calibrate;
  if (!cal) {
    panel.innerHTML = '<button type="button" class="btn btn-secondary btn-small" id="btnDistrictCalStart">Calibrate…</button>';
    document.getElementById('btnDistrictCalStart')?.addEventListener('click', () => startDistrictCalibrate());
    return;
  }
  panel.innerHTML =
    '<div>Cover sensor <strong>' + escapeHtml(cal.sensor) + '</strong></div>' +
    '<div>Difference now: <span class="nodes-cal-live" id="nodesCalLive">' + cal.livePct +
    '%</span> · Peak: <span id="nodesCalPeak">' + cal.peakPct + '%</span></div>' +
    '<div class="nodes-inline-actions" style="margin-top:6px">' +
    '<button type="button" class="btn btn-primary btn-small" id="btnDistrictCalUse">Use this reading</button> ' +
    '<button type="button" class="btn btn-secondary btn-small" id="btnDistrictCalReset">Reset peak</button> ' +
    '<button type="button" class="btn btn-secondary btn-small" id="btnDistrictCalStop">Stop</button>' +
    '</div>';
  document.getElementById('btnDistrictCalUse')?.addEventListener('click', () => applyCalibratePeakToDistrict());
  document.getElementById('btnDistrictCalReset')?.addEventListener('click', () => {
    if (nodesUi.calibrate) nodesUi.calibrate.peakPct = 0;
    const peak = document.getElementById('nodesCalPeak');
    if (peak) peak.textContent = '0%';
  });
  document.getElementById('btnDistrictCalStop')?.addEventListener('click', () => stopDistrictCalibrate());
}

function startDistrictCalibrate() {
  const d = nodesUi._modalDistrict;
  if (!d?.ref || !(d.sensors || []).length) {
    showStatus('Set Reference and sensors before calibrating', 'error');
    return;
  }
  // Pull latest name/pct into draft
  d.name = (document.getElementById('nodeDistrictName')?.value || '').trim() || d.name;
  d.sensitivityPct = clampSensitivityPct(document.getElementById('nodeDistrictPct')?.value);
  stopDistrictCalibrate();
  const sensor = d.sensors[0];
  const ix = nodesUi.editingDistrictIx;
  nodesUi.calibrate = {
    districtIx: ix,
    sensor,
    peakPct: 0,
    livePct: 0,
    timer: setInterval(() => pollDistrictSample(sensor), 350)
  };
  refreshDistrictCalPanel();
  pollDistrictSample(sensor);
}

async function pollDistrictSample(sensor) {
  const d = nodesUi._modalDistrict;
  if (!d?.ref || !nodesUi.calibrate) return;
  const data = await sendNodeTestCommand('SAMPLE ' + d.ref + ' ' + (sensor || d.sensors[0]));
  if (!data || data.pct == null || !nodesUi.calibrate) return;
  const pct = Number(data.pct) || 0;
  nodesUi.calibrate.livePct = pct;
  if (pct > nodesUi.calibrate.peakPct) nodesUi.calibrate.peakPct = pct;
  const live = document.getElementById('nodesCalLive');
  const peak = document.getElementById('nodesCalPeak');
  if (live) live.textContent = pct + '%';
  if (peak) peak.textContent = nodesUi.calibrate.peakPct + '%';
}

function applyCalibratePeakToDistrict() {
  const d = nodesUi._modalDistrict;
  if (!d || !nodesUi.calibrate) return;
  const peak = nodesUi.calibrate.peakPct || 0;
  d.sensitivityPct = clampSensitivityPct(Math.floor(peak * 0.7));
  const input = document.getElementById('nodeDistrictPct');
  if (input) input.value = String(d.sensitivityPct);
  showStatus('Sensitivity set to ' + d.sensitivityPct + '% (70% of peak ' + peak + '%)', 'success');
}

function renderAssignedDistrictCard(board, d, ix) {
  const findOn = nodesUi.highlightDistIx === ix;
  const card = document.createElement('div');
  card.className = 'nodes-output-card' + (findOn ? ' is-finding' : '');
  card.dataset.distIx = String(ix);
  card.innerHTML =
    '<div class="nodes-output-row-head">' +
    '<strong>' + escapeHtml(d.name || 'District') + '</strong>' +
    '<span class="nodes-output-tools">' +
    '<button type="button" class="nodes-icon-btn" data-dist-find title="Highlight pins on board" aria-label="Highlight pins">' +
    '<span aria-hidden="true">⌕</span></button>' +
    '<button type="button" class="nodes-icon-btn" data-dist-cfg title="Configure" aria-label="Configure">' +
    '<span aria-hidden="true">⚙</span></button>' +
    '</span></div>' +
    '<div class="nodes-output-meta">Ref ' + escapeHtml(d.ref || '—') +
    ' · ' + escapeHtml((d.sensors || []).join(', ') || '—') +
    ' · ' + clampSensitivityPct(d.sensitivityPct) + '%</div>';
  card.querySelector('[data-dist-cfg]')?.addEventListener('click', (ev) => {
    ev.stopPropagation();
    openDistrictModal(board, ix);
  });
  card.querySelector('[data-dist-find]')?.addEventListener('click', (ev) => {
    ev.stopPropagation();
    nodesUi.highlightOutIx = null;
    nodesUi.highlightDistIx = (nodesUi.highlightDistIx === ix) ? null : ix;
    applyPinHighlights(board);
    document.querySelectorAll('#nodesDistrictList .nodes-output-card').forEach((el) => {
      el.classList.toggle('is-finding', Number(el.dataset.distIx) === nodesUi.highlightDistIx);
    });
    document.querySelectorAll('#nodesOutputList .nodes-output-card').forEach((el) => {
      el.classList.remove('is-finding');
    });
  });
  return card;
}

function renderDistrictList(board) {
  const list = document.getElementById('nodesDistrictList');
  if (!list) return;
  const districts = boardOpticalDistricts(board);
  list.innerHTML = '';
  if (!districts.length) {
    list.innerHTML = '<p class="wifi-hint">No sensor districts yet. Choose <strong>Sensor district</strong> on the left to add one.</p>';
    return;
  }
  districts.forEach((d, ix) => {
    list.appendChild(renderAssignedDistrictCard(board, d, ix));
  });
  applyPinHighlights(board);
}

async function testTriggerValue(ix, field) {
  const t = nodeModalTriggers[ix];
  const pin = (nodesUi._modalPins || [])[0];
  if (!pin) {
    showStatus('No output pin selected', 'error');
    return;
  }
  const id = field?.id || 'deg';
  const min = field?.minimum != null ? field.minimum : 0;
  const max = field?.maximum != null ? field.maximum : (id === 'brightness' ? 100 : 180);
  const val = Number(t?.[id]);
  if (!Number.isFinite(val) || val < min || val > max) {
    showStatus('Enter ' + (field?.title || id) + ' ' + min + '–' + max + ' to test', 'error');
    return;
  }
  if (id === 'brightness') {
    await sendNodeTestCommand('PWM ' + pin + ' ' + val);
  } else {
    await sendNodeTestCommand('SERVO ' + pin + ' ' + val);
  }
}

/** Pins for barrier servos on the open modal (level crossing), else empty. */
function modalServoPinsForType(typeId) {
  const pins = nodesUi._modalPins || [];
  const slots = pinSlotsForType(typeId);
  if (slots?.length) {
    return slots
      .map((s, i) => ({ key: s.key, caps: s.caps || [], pin: pins[i] }))
      .filter((x) => x.pin && (x.caps.includes('servo') || /^barrier/i.test(x.key)))
      .map((x) => x.pin);
  }
  return pins.slice(0, 1).filter(Boolean);
}

async function testOutputParamDeg(paramId) {
  const host = document.getElementById('nodeOutputParams');
  const input = host?.querySelector('[data-param="' + paramId + '"]');
  const deg = Number(input?.value);
  if (!Number.isFinite(deg) || deg < 0 || deg > 180) {
    showStatus('Enter an angle 0–180° to test', 'error');
    return;
  }
  const type = document.getElementById('nodeOutputType')?.value || '';
  const servoPins = modalServoPinsForType(type);
  if (!servoPins.length) {
    showStatus('No barrier servo pins selected', 'error');
    return;
  }
  for (const pin of servoPins) {
    await sendNodeTestCommand('SERVO ' + pin + ' ' + deg);
  }
}

async function releaseSensorBoard(id) {
  try {
    const res = await fetch('/api/sensors/' + encodeURIComponent(id) + '/release', { method: 'POST' });
    const data = await readJsonOrError(res);
    if (!res.ok) throw new Error(data.error || res.statusText);
    showStatus('Sensor port released', 'success');
    loadSensorsBoards();
  } catch (err) {
    showStatus('Release failed: ' + err.message, 'error');
  }
}

async function resumeSensorBoard(id) {
  try {
    const res = await fetch('/api/sensors/' + encodeURIComponent(id) + '/resume', { method: 'POST' });
    const data = await readJsonOrError(res);
    if (!res.ok) throw new Error(data.error || res.statusText);
    showStatus('Sensor board resumed', 'success');
    loadSensorsBoards();
  } catch (err) {
    showStatus('Resume failed: ' + err.message, 'error');
  }
}

async function removeShimBoard(id) {
  try {
    const res = await fetch('/api/sensors/' + encodeURIComponent(id) + '/remove', { method: 'POST' });
    const data = await readJsonOrError(res);
    if (!res.ok) throw new Error(data.error || res.statusText);
    showStatus('Virtual board removed', 'success');
    if (nodesUi.configureId === id) setPageHash(nodesHash('view'));
    else loadSensorsBoards();
  } catch (err) {
    showStatus('Remove failed: ' + err.message, 'error');
  }
}

async function simulateShimChannel(id, hardwareId, state) {
  try {
    const res = await fetch('/api/sensors/' + encodeURIComponent(id) + '/simulate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ hardwareId, state })
    });
    const data = await readJsonOrError(res);
    if (!res.ok) throw new Error(data.error || res.statusText);
    loadSensorsBoards();
  } catch (err) {
    showStatus('Simulate failed: ' + err.message, 'error');
  }
}

async function pollSensorFlashStatus(expectedPrefix) {
  const logEl = document.getElementById('sensorFlashLog');
  try {
    const res = await fetch('/api/sensors/flash/status');
    const data = await res.json();
    if (logEl) {
      logEl.textContent = (data.log || []).join('\n') + (data.error ? '\nERROR: ' + data.error : '');
    }
    if (data.state === 'success' || data.state === 'failed' || data.state === 'idle') {
      if (sensorFlashPollTimer) {
        clearInterval(sensorFlashPollTimer);
        sensorFlashPollTimer = null;
      }
      await loadSensorsBoards();
      if (data.state === 'success') {
        showStatus('Flash complete', 'success');
        const prefix = expectedPrefix || data.prefix;
        const board = (sensorsPageCache?.boards || []).find((b) =>
          (prefix && b.prefix === prefix) || b.status === 'listening');
        if (board) setPageHash(nodesHash('configure', board.id));
        else setPageHash(nodesHash('view'));
      } else if (data.state === 'failed') {
        showStatus('Flash failed: ' + (data.error || 'see log'), 'error');
      }
    }
  } catch (err) { /* ignore */ }
}

function bindNodesUi() {
  document.getElementById('nodesAuditSearch')?.addEventListener('input', () => {
    if (nodesUi.screen === 'audit') renderNodesAudit();
  });
  document.querySelectorAll('[data-add-method]').forEach((btn) => {
    btn.addEventListener('click', () => {
      nodesUi.addMethod = btn.getAttribute('data-add-method');
      nodesUi.addStep = 'details';
      nodesUi.addDraft = {};
      loadSensorFlashPorts().then(() => {
        renderNodesAdd();
      });
    });
  });
  document.getElementById('btnNodesAddBack')?.addEventListener('click', () => {
    nodesUi.addStep = 'method';
    nodesUi.addMethod = null;
    nodesUi.addDraft = {};
    renderNodesAdd();
  });
  document.getElementById('btnNodesAddNext')?.addEventListener('click', () => {
    if (!validateAddDetails()) return;
    nodesUi.addStep = 'action';
    renderNodesAdd();
  });
  document.getElementById('btnNodesAddCancel')?.addEventListener('click', () => {
    setPageHash(nodesHash('view'));
  });
  document.getElementById('btnNodesAddRun')?.addEventListener('click', runNodesAddAction);
  document.getElementById('btnNodesConfigDone')?.addEventListener('click', () => {
    closeDistrictModal();
    nodesUi.pickType = null;
    setPageHash(nodesHash('view'));
  });
  document.getElementById('btnNodeSaveDistrict')?.addEventListener('click', () => saveDistrictModal());
  document.getElementById('btnNodeDeleteDistrict')?.addEventListener('click', () => deleteDistrictModal());
  document.getElementById('btnDistrictPickRef')?.addEventListener('click', () => beginDistrictPinPick('ref'));
  document.getElementById('btnDistrictPickSensors')?.addEventListener('click', () => beginDistrictPinPick('sensors'));
  document.querySelectorAll('[data-district-modal-close]').forEach((el) => {
    el.addEventListener('click', () => closeDistrictModal());
  });
  document.getElementById('btnDistrictCalStart')?.addEventListener('click', () => startDistrictCalibrate());
  document.getElementById('btnNodeAddTrigger')?.addEventListener('click', () => {
    const type = document.getElementById('nodeOutputType')?.value;
    const meta = outputTypeMeta(type);
    const mode = meta?.triggerMode;
    if (!meta || (mode !== 'positions' && mode !== 'patterns')) return;
    if (nodeModalTriggers.length >= (meta.maxTriggers || 16)) return;
    const last = nodeModalTriggers[nodeModalTriggers.length - 1] || {};
    const n = nodeModalTriggers.length + 1;
    if (mode === 'patterns') {
      nodeModalTriggers.push(multiOutDefaultTrigger(Number(last.dcc) || 1, nodeModalTriggers.length + 1));
    } else {
      const next = {
        label: (type === 'fade' ? 'Level ' : 'Position ') + n,
        dcc: Number(last.dcc) || 1,
        direction: 'Forward'
      };
      const vf = positionsValueField(meta);
      if (vf?.id === 'brightness') {
        next.brightness = last.brightness != null ? Number(last.brightness) : 50;
      } else {
        next.deg = last.deg != null ? Number(last.deg) : 90;
      }
      nodeModalTriggers.push(next);
    }
    renderNodeTriggersEditor();
  });
  document.getElementById('btnNodeSaveOutput')?.addEventListener('click', saveNodeOutputCfg);
  document.getElementById('btnNodeDeleteOutput')?.addEventListener('click', deleteNodeOutputCfg);
  document.querySelectorAll('[data-modal-close]').forEach((el) => {
    el.addEventListener('click', closeNodesModal);
  });
}

function refreshSensorsPage() {
  loadSensorsToolchain();
  loadSensorFlashPorts().then(() => loadSensorsBoards()).then(() => {
    if (currentPageFromHash() === 'sensors') syncNodesScreenFromHash();
  });
}

bindNodesUi();

// Initialize on page load
ensureDefaultPageHash();
setupHashNavigation();
updatePageSectionState();
updatePageNavState();
loadSystems();
loadPorts();
loadConnections();
loadUpdateStatus();
loadWifiStatus();
scanWifi();
refreshSensorsPage();
setInterval(loadConnections, 5000);
setInterval(loadPorts, 5000);
setInterval(loadUpdateStatus, 15000);
setInterval(loadWifiStatus, 15000);
let sensorsPollTimer = null;
function scheduleSensorsPoll() {
  if (sensorsPollTimer) clearTimeout(sensorsPollTimer);
  const ms = sensorsPageCache?.outputAnimating ? 250 : 1500;
  sensorsPollTimer = setTimeout(async () => {
  if (currentPageFromHash() === 'sensors') {
      await loadSensorFlashPorts();
      await loadSensorsBoards();
  }
    scheduleSensorsPoll();
  }, ms);
}
scheduleSensorsPoll();
connectEventStream();
connectWebSocket();
initThrottleFunctions();
setupThrottleSliderDragTracking();

