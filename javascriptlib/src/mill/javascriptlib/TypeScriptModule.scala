package mill.javascriptlib
import mill._

trait TypeScriptModule extends Module {
  def moduleDeps: Seq[TypeScriptModule] = Nil

  def npmDeps: T[Seq[String]] = Task { Seq.empty[String] }

  def npmDevDeps: T[Seq[String]] = Task { Seq(
    "typescript@5.6.3",
    "@types/node@22.7.8",
    "esbuild@0.24.0"
  ) }

  def transitiveNpmDeps: T[Seq[String]] = Task {
    Task.traverse(moduleDeps)(_.npmDeps)().flatten
  }

  def transitiveNpmDevDeps: T[Seq[String]] = Task {
    Task.traverse(moduleDeps)(_.npmDevDeps)().flatten
  }

  def npmInstall = Task {
    os.call((
      "npm",
      "install",
      npmDeps() ++ transitiveNpmDeps()
    ))
    os.call((
      "npm",
      "install",
      "--save-dev",
      npmDevDeps() ++ transitiveNpmDevDeps()
    ))
    PathRef(Task.dest)
  }

  def sources = Task.Source(millSourcePath / "src")
  def allSources = Task { os.walk(sources().path).filter(file => file.ext == "ts" || file.ext == "tsx" || file.ext == ".d.ts").map(PathRef(_)) }

  def tscArgs = Task { Seq.empty[String] }

  def compile: T[(PathRef, PathRef)] = Task {
    val nodeTypes = npmInstall().path / "node_modules/@types"
    val javascriptOut = Task.dest / "javascript"
    val declarationsOut = Task.dest / "declarations"

    val upstreamPaths =
      for (((jsDir, dTsDir), mod) <- Task.traverse(moduleDeps)(_.compile)().zip(moduleDeps))
        yield (mod.millSourcePath.subRelativeTo(Task.workspace).toString + "/*", dTsDir.path)

    val allPaths = upstreamPaths ++ Seq("*" -> sources().path, "*" -> npmInstall().path)

    os.write(
      Task.dest / "tsconfig.json",
      ujson.Obj(
        "compilerOptions" -> ujson.Obj(
          "outDir" -> javascriptOut.toString,
//          "declaration" -> true,
//          "declarationDir" -> declarationsOut.toString,
          "typeRoots" -> ujson.Arr(nodeTypes.toString),
          "paths" -> ujson.Obj.from(allPaths.map { case (k, v) => (k, ujson.Arr(s"$v/*")) }),
          // TODO: These should be configurable
          "target" -> "ES2017",
          "lib" -> ujson.Arr("dom", "dom.iterable", "esnext"),
          "plugins" -> ujson.Arr(ujson.Obj(
            "name" -> "next"
          )),
          "allowJs" -> true,
          "skipLibCheck" -> true,
          "strict" -> true,
          "noEmit" -> true,
          "esModuleInterop" -> true,
          "module" -> "esnext",
          "moduleResolution" -> "bundler",
          "resolveJsonModule" -> true,
          "isolatedModules" -> true,
          "jsx" -> "preserve",
          "incremental" -> true,
          "paths" -> ujson.Obj(
            "@/*" -> ujson.Arr("./src/*") // FIXME: this must be wrong since it should be relative to somewhere in the target, I think
          )
        ),
        "files" -> allSources().map(_.path.toString)
      )
    )

    os.call((npmInstall().path / "node_modules/typescript/bin/tsc", tscArgs()))

    (PathRef(javascriptOut), PathRef(declarationsOut))
  }

  def mainFileName = Task { s"${millSourcePath.last}.js" }

  def prepareRun = Task.Anon {
    val upstream = Task.traverse(moduleDeps)(_.compile)().zip(moduleDeps)
    for (((jsDir, tTsDir), mod) <- upstream) {
      os.copy(jsDir.path, Task.dest / mod.millSourcePath.subRelativeTo(Task.workspace))
    }
    val mainFile = compile()._1.path / mainFileName()
    val env = Map("NODE_PATH" -> Seq(".", compile()._1.path, npmInstall().path).mkString(":"))
    (mainFile, env)
  }

  def run(args: mill.define.Args) = Task.Command {
    val (mainFile, env) = prepareRun()
    os.call(("node", mainFile, args.value), stdout = os.Inherit, env = env)
  }

  def bundle = Task {
    val (mainFile, env) = prepareRun()
    val esbuild = npmInstall().path / "node_modules/esbuild/bin/esbuild"
    val bundle = Task.dest / "bundle.js"
    os.call((esbuild, mainFile, "--bundle", s"--outfile=$bundle"), env = env)
    PathRef(bundle)
  }
}
