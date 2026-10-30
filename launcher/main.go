// Command launcher is the single-file Delver Online executable: it carries delveronline.jar inside itself and
// starts the modded game (or the dedicated server) with the Java runtime that ships with Delver, so no Java needs
// to be installed. Built by `./gradlew release`; the mode (client or server) is fixed at build time.
package main

import (
	"bufio"
	"crypto/sha256"
	_ "embed"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
)

//go:embed delveronline.jar
var modJar []byte

// mode is "client" or "server"; set with -ldflags "-X main.mode=server".
var mode = "client"

const (
	clientMain = "com.interrupt.dungeoneer.DesktopStarter"
	serverMain = "com.delveronline.server.ServerMain"
)

func main() {
	delverDir, err := findDelver()
	if err != nil {
		fail(err.Error())
	}
	java, err := findJava(delverDir)
	if err != nil {
		fail(err.Error())
	}
	jar, err := extractJar()
	if err != nil {
		fail("Could not unpack the mod: " + err.Error())
	}

	dataDir := clientDataDir()
	mainClass := clientMain
	var jvm []string
	if mode == "server" {
		dataDir = os.Getenv("DELVERONLINE_SERVER_DATA")
		if dataDir == "" {
			dataDir = filepath.Join(clientDataDir(), "server")
		}
		mainClass = serverMain
		jvm = append(jvm, "-Xmx1g")
	}
	if err := os.MkdirAll(dataDir, 0o755); err != nil {
		fail("Could not create " + dataDir + ": " + err.Error())
	}
	if mode == "client" {
		prepareSteamAppID(delverDir, dataDir)
	}

	jvm = append(jvm, strings.Fields(os.Getenv("DELVERONLINE_JVM_OPTS"))...)
	args := append([]string{java}, jvm...)
	args = append(args,
		"-javaagent:"+jar,
		"-cp", filepath.Join(delverDir, "delver.jar")+string(os.PathListSeparator)+jar,
		"-Duser.country=US", "-Duser.language=en",
		mainClass)
	args = append(args, os.Args[1:]...)
	if err := run(java, args, dataDir); err != nil {
		fail("Could not start Java (" + java + "): " + err.Error())
	}
}

// clientDataDir is where the modded game keeps its options, saves and last server (never the Steam saves).
func clientDataDir() string {
	if d := os.Getenv("DELVERONLINE_DATA"); d != "" {
		return d
	}
	if runtime.GOOS == "windows" {
		if d := os.Getenv("APPDATA"); d != "" {
			return filepath.Join(d, "DelverOnline")
		}
	}
	if d := os.Getenv("XDG_DATA_HOME"); d != "" {
		return filepath.Join(d, "DelverOnline")
	}
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".local", "share", "DelverOnline")
}

// prepareSteamAppID lets the game sign in to Steam when started outside Steam, unless DELVERONLINE_NO_STEAM is set
// (Steam closes a second running copy of Delver; a copy without the sign-in can run next to it).
func prepareSteamAppID(delverDir, dataDir string) {
	target := filepath.Join(dataDir, "steam_appid.txt")
	if v := os.Getenv("DELVERONLINE_NO_STEAM"); v != "" && v != "0" {
		os.Remove(target)
		return
	}
	if _, err := os.Stat(target); err == nil {
		return
	}
	if b, err := os.ReadFile(filepath.Join(delverDir, "steam_appid.txt")); err == nil {
		os.WriteFile(target, b, 0o644)
	}
}

// findDelver locates the Delver install: DELVER_DIR, next to this executable, or a Steam library.
func findDelver() (string, error) {
	var tried []string
	check := func(dir string) bool {
		if dir == "" {
			return false
		}
		tried = append(tried, dir)
		_, err := os.Stat(filepath.Join(dir, "delver.jar"))
		return err == nil
	}
	if d := os.Getenv("DELVER_DIR"); d != "" {
		if check(d) {
			return d, nil
		}
		return "", fmt.Errorf("Delver not found in DELVER_DIR (%s).", d)
	}
	if exe, err := os.Executable(); err == nil {
		if resolved, err := filepath.EvalSymlinks(exe); err == nil {
			exe = resolved
		}
		dir := filepath.Dir(exe)
		for _, d := range []string{dir, filepath.Dir(dir)} {
			if check(d) {
				return d, nil
			}
		}
	}
	for _, lib := range steamLibraries() {
		d := filepath.Join(lib, "steamapps", "common", "Delver")
		if check(d) {
			return d, nil
		}
	}
	return "", fmt.Errorf("Delver was not found. Install it with Steam, put this program in the Delver folder, "+
		"or set DELVER_DIR to the Delver folder.\nLooked in:\n  %s", strings.Join(tried, "\n  "))
}

var libraryPath = regexp.MustCompile(`"path"\s+"([^"]+)"`)

// steamLibraries lists the Steam roots and every library folder they know about.
func steamLibraries() []string {
	seen := map[string]bool{}
	var libs []string
	add := func(p string) {
		p = filepath.Clean(p)
		if p != "." && !seen[p] {
			seen[p] = true
			libs = append(libs, p)
		}
	}
	for _, root := range steamRoots() {
		add(root)
		f, err := os.Open(filepath.Join(root, "steamapps", "libraryfolders.vdf"))
		if err != nil {
			continue
		}
		s := bufio.NewScanner(f)
		for s.Scan() {
			if m := libraryPath.FindStringSubmatch(s.Text()); m != nil {
				add(strings.ReplaceAll(m[1], `\\`, `\`))
			}
		}
		f.Close()
	}
	return libs
}

// findJava finds the Java runtime that ships with Delver; a system Java is only a last resort.
func findJava(delverDir string) (string, error) {
	names := javaNames()
	for _, rel := range bundledJavaDirs() {
		for _, n := range names {
			p := filepath.Join(delverDir, rel, n)
			if isFile(p) {
				return p, nil
			}
		}
	}
	// any bin/java inside the install (the runtime folder name differs between platforms and versions)
	var found string
	filepath.Walk(delverDir, func(p string, info os.FileInfo, err error) error {
		if found != "" {
			return filepath.SkipAll
		}
		if err != nil {
			return nil
		}
		if info.IsDir() {
			rel, _ := filepath.Rel(delverDir, p)
			if strings.Count(rel, string(os.PathSeparator)) >= 4 || info.Name() == "save" {
				return filepath.SkipDir
			}
			for _, n := range names {
				if c := filepath.Join(p, "bin", n); isFile(c) {
					found = c
					return filepath.SkipDir
				}
			}
		}
		return nil
	})
	if found != "" {
		return found, nil
	}
	if home := os.Getenv("JAVA_HOME"); home != "" {
		for _, n := range names {
			if p := filepath.Join(home, "bin", n); isFile(p) {
				return p, nil
			}
		}
	}
	return "", fmt.Errorf("Delver's Java runtime was not found in %s. Verify the game files in Steam.", delverDir)
}

func isFile(p string) bool {
	info, err := os.Stat(p)
	return err == nil && !info.IsDir()
}

// extractJar writes the embedded mod jar to the user cache (once per version) and returns its path.
func extractJar() (string, error) {
	sum := sha256.Sum256(modJar)
	version := hex.EncodeToString(sum[:])[:16]
	base, err := os.UserCacheDir()
	if err != nil {
		base = os.TempDir()
	}
	dir := filepath.Join(base, "DelverOnline")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	path := filepath.Join(dir, "delveronline-"+version+".jar")
	if info, err := os.Stat(path); err == nil && info.Size() == int64(len(modJar)) {
		return path, nil
	}
	tmp, err := os.CreateTemp(dir, "unpack-*.tmp")
	if err != nil {
		return "", err
	}
	_, werr := tmp.Write(modJar)
	cerr := tmp.Close()
	if werr != nil || cerr != nil {
		os.Remove(tmp.Name())
		if werr != nil {
			return "", werr
		}
		return "", cerr
	}
	if err := os.Rename(tmp.Name(), path); err != nil {
		os.Remove(tmp.Name())
		if _, statErr := os.Stat(path); statErr != nil {
			return "", err // another launcher may have won the race; only fail if the jar is really missing
		}
	}
	// older versions are no longer needed (a running copy keeps working on Linux; on Windows removal just fails)
	if old, _ := filepath.Glob(filepath.Join(dir, "delveronline-*.jar")); old != nil {
		for _, o := range old {
			if o != path {
				os.Remove(o)
			}
		}
	}
	return path, nil
}
