-- OpenGGF 0.6 compatibility forwarder. TraceChaser owns all recorder logic.
local M = {}

local function shell_quote(value)
  return "'" .. string.gsub(value, "'", "'\\''") .. "'"
end

local function repo_root()
  local source = debug.getinfo(1, "S").source
  local path = string.sub(source, 1, 1) == "@" and string.sub(source, 2) or source
  local root = string.match(path, "^(.*)[/\\]tools[/\\]tracechaser%-forward%.lua$")
  if not root or root == "" then
    local handle = assert(io.popen("git rev-parse --show-toplevel 2>/dev/null", "r"))
    root = string.gsub(handle:read("*a"), "%s+$", "")
    local ok = handle:close()
    if ok ~= true and ok ~= 0 then
      io.stderr:write("TraceChaser: cannot resolve the OpenGGF checkout root\n")
      os.exit(4)
    end
  end
  return root
end

function M.run(relative)
  local root = repo_root()
  local command = "bash " .. shell_quote(root .. "/tools/tracechaser-bootstrap.sh")
      .. " --require " .. shell_quote(relative) .. " 2>&1"
  local handle = assert(io.popen(command, "r"))
  local output = handle:read("*a")
  local ok, why, code = handle:close()
  if ok ~= true and ok ~= 0 then
    io.stderr:write(output)
    os.exit(code or 4)
  end
  local target = string.gsub(output, "%s+$", "")
  return dofile(target)
end

return M
