# Installer maven plugin

> Build standalone application from an artifact and its dependencies

This plugin does a similar thing as `maven-shade-plugin`:

* it can create uber jar containing all dependencies
* it can create a single executable file containing jar itself

Difference is that `installer-maven-plugin` is not designed to be a part of
project's build process, rather to be invoked for **already published**
artifacts.

## Usage

This plugin is used outside of project (similarly to `archetype:generate`).

Example invocation:

```shell script
# this plugin is not related to any project
cd /tmp

mvn pl.matsuo.installer:installer-maven-plugin:install \
  -DgroupId=pl.matsuo \
  -DartifactId=matsuo-util-desktop \
  -Dversion=0.1.4
```

Will resolve `pl.matsuo:matsuo-util-desktop:0.1.4` and all its dependencies and
create uber jar from all these elements

```shell script
matsuo-util-desktop_0.1.4.jar
```

Additionally executable file will be created

```shell script
matsuo-util-desktop_0.1.4
```

Which should work just by invoking it

```shell script
./matsuo-util-desktop_0.1.4
```

Executable file works only if artifact specified `META-INF/MANIFEST.MF` file
and it contains `MainClass` definition (see
[Maven: make the jar executable](https://maven.apache.org/shared/maven-archiver/examples/classpath.html#Make).

