# Workflow Engine POC — Rakefile
# Auto-detecta docker/podman. Encapsula tests y deploy en containers.

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

desc "Lanzar tests (unit + E2E) con coverage"
task :test do
  sh "#{engine} rm -fvi workflow-engine-test-runner workflow-engine-test-db"
  compose("run", "--rm", "test-runner", "clojure", "-M:coverage")
  sh "#{engine} cp workflow-engine-test-runner:/app/target/coverage ./target/coverage" rescue nil
  puts "\n=> Coverage report: target/coverage/index.html"
end

desc "Build imagen y levantar app (deploy)"
task :deploy do
  compose("build", "app")
  compose("up", "-d", "db", "app")
  puts "=> App: http://localhost:3000/api/v1/health"
end

desc "Ejecutar demo interactiva (CLI)"
task :demo do
  compose("-f", "demo/compose.demo.yml", "build", "demo")
  sh "#{engine}-compose -f demo/compose.demo.yml run --rm demo clojure -M:demo -m demo.core"
end

desc "Levantar nREPL para REPL demo (puerto 7888)"
task :demo_repl do
  compose("-f", "demo/compose.demo.yml", "up", "-d", "db")
  sleep 3
  compose("-f", "demo/compose.demo.yml", "run", "--rm", "demo",
          "clojure", "-M:demo-repl")
end

desc "Limpiar todo"
task :clean do
  rm_rf "target"
  compose("down", "-v")
  compose("-f", "demo/compose.demo.yml", "down", "-v") rescue nil
end

task default: :test
