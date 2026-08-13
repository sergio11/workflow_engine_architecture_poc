# Workflow Engine POC — Rakefile
# Auto-detects docker/podman. Encapsulates tests and deploy in containers.

def engine
  @engine ||= if system("podman-compose version")
                "podman"
              else
                "docker"
              end
end

def compose(*args)
  sh "#{engine}-compose #{args.join(' ')}"
end

def compose_no_fail(*args)
  system("#{engine}-compose #{args.join(' ')}")
end

desc "Run tests (unit + E2E) with coverage"
task :test do
  compose("down", "-v") rescue nil
  compose_no_fail("run", "test-runner", "clojure", "-M:coverage")
  sh "#{engine} cp workflow-engine-test-runner:/app/target/coverage ./target/coverage" rescue nil
  sh "#{engine} rm -fvi workflow-engine-test-runner" rescue nil
  puts "\n=> Coverage report: target/coverage/index.html"
end

desc "Build image and start app (deploy)"
task :deploy do
  compose("build", "app")
  compose("up", "-d", "db", "app")
  puts "=> App: http://localhost:3000/api/v1/health"
end

desc "Run interactive demo (CLI)"
task :demo do
  compose("-f", "demo/compose.demo.yml", "down", "-v") rescue nil
  compose("-f", "demo/compose.demo.yml", "up", "-d", "db")
  sleep 5
  sh "#{engine} build -t workflow-engine-demo -f demo/Dockerfile.demo ."
  sh "#{engine} run --rm --add-host=host.containers.internal:host-gateway -e DB_HOST=host.containers.internal -e DB_PORT=5432 workflow-engine-demo"
end

desc "Start nREPL for REPL demo (port 7888)"
task :demo_repl do
  compose("-f", "demo/compose.demo.yml", "down", "-v") rescue nil
  compose("-f", "demo/compose.demo.yml", "up", "-d", "db")
  sleep 5
  sh "#{engine} build -t workflow-engine-demo -f demo/Dockerfile.demo ."
  sh "#{engine} run --rm --add-host=host.containers.internal:host-gateway -e DB_HOST=host.containers.internal -e DB_PORT=5432 -p 7888:7888 workflow-engine-demo clojure -M:demo-repl"
end

desc "Clean everything"
task :clean do
  rm_rf "target"
  sh "#{engine} rm -f workflow-engine-demo workflow-engine-demo-db" rescue nil
  compose("down", "-v")
  compose("-f", "demo/compose.demo.yml", "down", "-v") rescue nil
end

task default: :test
