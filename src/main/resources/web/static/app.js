let systems = [];
let ports = [];

const PAGE_HASHES = ['#dcc', '#sensors', '#wifi', '#updates'];

function ensureDefaultPageHash() {
  if (!PAGE_HASHES.includes(window.location.hash)) {
    window.history.replaceState(null, '', '#dcc');
  }
}

function currentPageFromHash() {
  return PAGE_HASHES.includes(window.location.hash) ? window.location.hash.slice(1) : 'dcc';
}

function updatePageSectionState() {
  const current = currentPageFromHash();
  document.querySelectorAll('.page-section').forEach((section) => {
    const isActive = section.getAttribute('data-page') === current;
    section.classList.toggle('is-active', isActive);
  });
}

function updatePageNavState() {
  const current = currentPageFromHash();
  document.querySelectorAll('[data-page-link]').forEach((link) => {
    const isActive = link.getAttribute('data-page-link') === current;
    link.classList.toggle('is-active', isActive);
  });
}

function setPageHash(hash) {
  if (window.location.hash === hash) {
    updatePageSectionState();
    updatePageNavState();
    return;
  }
  window.history.pushState(null, '', hash);
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

// --- Sensors page ---
let sensorFlashPollTimer = null;

function updateSensorFlashChannels() {
  const prefix = (document.getElementById('sensorFlashPrefix')?.value || '').trim().toUpperCase();
  const el = document.getElementById('sensorFlashChannels');
  if (!el) return;
  if (!/^[A-Z]{1,2}$/.test(prefix)) {
    el.textContent = 'Channels: enter a valid prefix';
    return;
  }
  const optical = [];
  for (let i = 0; i <= 6; i++) optical.push(prefix + i);
  const digital = [];
  for (let p = 2; p <= 12; p++) digital.push(prefix + 'D' + p);
  el.textContent = 'Optical: ' + optical.join(', ') + ' · Digital (INPUT_PULLUP): ' + digital.join(', ');
}

function renderSensorLedGroup(title, channels) {
  if (!channels.length) return '';
  const leds = channels.map((ch) => {
    const on = ch.state === 1;
    const kind = ch.kind === 'digital' ? 'kind-digital' : 'kind-analog';
    return '<div class="sensors-led ' + kind + (on ? ' on' : '') + '" title="' +
      (ch.id || '') + ': ' + (on ? 'DETECTED' : 'CLEAR') + '">' +
      '<span class="sensors-led-lamp" aria-hidden="true"></span>' +
      '<span class="sensors-led-label">' + (ch.label || '') + '</span>' +
      '<span class="sensors-led-id">' + (ch.id || '') + '</span>' +
      '</div>';
  }).join('');
  return '<div class="sensors-led-group">' +
    '<div class="sensors-led-group-label">' + title + '</div>' +
    '<div class="sensors-led-row">' + leds + '</div></div>';
}

function renderSensorLedPanel(channels) {
  const list = channels || [];
  const optical = list.filter((c) => c.kind !== 'digital');
  const digital = list.filter((c) => c.kind === 'digital');
  if (!optical.length && !digital.length) {
    return '<p class="wifi-hint">No channel map yet (waiting for prefix / READY).</p>';
  }
  return '<div class="sensors-led-panel">' +
    renderSensorLedGroup('Optical A0–A6', optical) +
    renderSensorLedGroup('Digital D2–D12 (pull-up)', digital) +
    '</div>';
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
    if (prev && (data.ports || []).includes(prev)) {
      select.value = prev;
    }
  } catch (err) {
    /* ignore */
  }
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
  const list = document.getElementById('sensorsBoardList');
  const candidatesEl = document.getElementById('sensorsCandidateList');
  const stubs = document.getElementById('sensorsTransportStubs');
  if (!list) return;
  try {
    const res = await fetch('/api/sensors');
    const data = await res.json();
    const boards = data.boards || [];
    if (!boards.length) {
      list.innerHTML = '<p class="wifi-hint">No USB sensor boards connected yet.</p>';
    } else {
      list.innerHTML = '';
      boards.forEach((b) => {
        const row = document.createElement('div');
        row.className = 'sensors-board-row';
        const prefix = b.prefix || '—';
        row.innerHTML =
          '<div class="sensors-board-header">' +
          '<div><strong>Prefix ' + prefix + '</strong> · ' + (b.transport || 'usb') +
          ' · <code>' + (b.endpoint || '') + '</code> · ' + (b.status || '') + '</div>' +
          '<div class="sensors-board-actions"></div></div>' +
          renderSensorLedPanel(b.channels);
        const actions = row.querySelector('.sensors-board-actions');
        if (b.status === 'listening') {
          const rel = document.createElement('button');
          rel.className = 'btn btn-secondary btn-small';
          rel.textContent = 'Release port';
          rel.onclick = () => releaseSensorBoard(b.id);
          actions.appendChild(rel);
          const flash = document.createElement('button');
          flash.className = 'btn btn-primary btn-small';
          flash.textContent = 'Flash again';
          flash.onclick = () => {
            document.getElementById('sensorFlashPort').value = b.endpoint;
            if (b.prefix) document.getElementById('sensorFlashPrefix').value = b.prefix;
            updateSensorFlashChannels();
            window.scrollTo({ top: document.getElementById('btnSensorFlash').offsetTop - 40, behavior: 'smooth' });
          };
          actions.appendChild(flash);
        } else if (b.status === 'released' || b.status === 'flashing') {
          const resume = document.createElement('button');
          resume.className = 'btn btn-primary btn-small';
          resume.textContent = 'Resume';
          resume.disabled = b.status === 'flashing';
          resume.onclick = () => resumeSensorBoard(b.id);
          actions.appendChild(resume);
        }
        list.appendChild(row);
      });
    }

    if (candidatesEl) {
      const candidates = data.candidates || [];
      if (!candidates.length) {
        candidatesEl.innerHTML = '<p class="wifi-hint">No USB serial candidates waiting for probe.</p>';
      } else {
        candidatesEl.innerHTML = '';
        candidates.forEach((c) => {
          const row = document.createElement('div');
          row.className = 'sensors-board-row';
          const cool = c.cooldownMs ? (' · auto-retry in ~' + Math.ceil(c.cooldownMs / 1000) + 's') : '';
          row.innerHTML =
            '<div class="sensors-board-header">' +
            '<div><strong>' + (c.name || 'USB candidate') + '</strong> · <code>' +
            (c.port || c.endpoint || '') + '</code>' + cool + '</div>' +
            '<div class="sensors-board-actions"></div></div>';
          const actions = row.querySelector('.sensors-board-actions');
          const probeBtn = document.createElement('button');
          probeBtn.className = 'btn btn-primary btn-small';
          probeBtn.textContent = 'Probe now';
          probeBtn.onclick = () => probeSensorPort(c.port || c.endpoint, probeBtn);
          actions.appendChild(probeBtn);
          candidatesEl.appendChild(row);
        });
      }
    }

    if (stubs && data.transports) {
      stubs.innerHTML = '';
      data.transports.forEach((t) => {
        if (t.id === 'usb') return;
        const card = document.createElement('div');
        card.className = 'sensors-stub-card';
        card.innerHTML = '<strong>' + (t.label || t.id) + '</strong><br><span class="wifi-hint">' +
          (t.note || 'Coming soon') + '</span>';
        stubs.appendChild(card);
      });
    }
  } catch (err) {
    list.innerHTML = '<p class="wifi-hint">Failed to load boards: ' + err.message + '</p>';
  }
}

async function probeSensorPort(port, buttonEl) {
  if (!port) return;
  if (buttonEl) {
    buttonEl.disabled = true;
    buttonEl.textContent = 'Probing…';
  }
  try {
    const res = await fetch('/api/sensors/probe', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ port })
    });
    const data = await readJsonOrError(res);
    if (!res.ok || !data.ok) {
      throw new Error(data.error || ('Probe result: ' + (data.systemType || res.statusText)));
    }
    if (data.systemType === 'panelsdcc-sensor-board') {
      showStatus('Sensor board on ' + port + (data.prefix ? ' (prefix ' + data.prefix + ')' : ''), 'success');
    } else if (data.systemType === 'dccpp') {
      showStatus('Port ' + port + ' is DCC++ — use the DCC page to connect', 'success');
    } else {
      showStatus('Probe finished: ' + (data.systemType || 'unknown'), 'success');
    }
    loadSensorsBoards();
  } catch (err) {
    showStatus('Probe failed: ' + err.message, 'error');
    loadSensorsBoards();
  } finally {
    if (buttonEl) {
      buttonEl.disabled = false;
      buttonEl.textContent = 'Probe now';
    }
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

async function flashSensorBoard() {
  const port = document.getElementById('sensorFlashPort')?.value;
  const prefix = (document.getElementById('sensorFlashPrefix')?.value || '').trim().toUpperCase();
  const logEl = document.getElementById('sensorFlashLog');
  if (!port) {
    showStatus('Select a port to flash', 'error');
    return;
  }
  if (!/^[A-Z]{1,2}$/.test(prefix)) {
    showStatus('Prefix must be A–Z or AA–ZZ', 'error');
    return;
  }
  try {
    const res = await fetch('/api/sensors/flash', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ port, prefix })
    });
    const data = await readJsonOrError(res);
    if (!res.ok && res.status !== 202) {
      throw new Error(data.error || res.statusText);
    }
    showStatus('Flash started for prefix ' + prefix, 'success');
    if (sensorFlashPollTimer) clearInterval(sensorFlashPollTimer);
    sensorFlashPollTimer = setInterval(pollSensorFlashStatus, 1000);
    pollSensorFlashStatus();
  } catch (err) {
    showStatus('Flash failed: ' + err.message, 'error');
    if (logEl) logEl.textContent = err.message;
  }
}

async function pollSensorFlashStatus() {
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
      loadSensorsBoards();
      if (data.state === 'success') {
        showStatus('Flash complete', 'success');
      } else if (data.state === 'failed') {
        showStatus('Flash failed: ' + (data.error || 'see log'), 'error');
      }
    }
  } catch (err) {
    /* ignore poll errors */
  }
}

document.getElementById('sensorFlashPrefix')?.addEventListener('input', updateSensorFlashChannels);
document.getElementById('btnSensorFlash')?.addEventListener('click', flashSensorBoard);

function refreshSensorsPage() {
  loadSensorsToolchain();
  loadSensorsBoards();
  loadSensorFlashPorts();
  updateSensorFlashChannels();
}

const _origUpdatePageSectionState = updatePageSectionState;
updatePageSectionState = function () {
  _origUpdatePageSectionState();
  if (currentPageFromHash() === 'sensors') {
    refreshSensorsPage();
  }
};

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
setInterval(() => {
  if (currentPageFromHash() === 'sensors') {
    loadSensorsBoards();
    loadSensorFlashPorts();
  }
}, 1500);
connectEventStream();
connectWebSocket();
initThrottleFunctions();
setupThrottleSliderDragTracking();

