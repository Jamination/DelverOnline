//go:build !windows

package main

import (
	"fmt"
	"os"
	"path/filepath"
	"syscall"
)

func steamRoots() []string {
	home, _ := os.UserHomeDir()
	return []string{
		filepath.Join(home, ".local", "share", "Steam"),
		filepath.Join(home, ".steam", "steam"),
		filepath.Join(home, ".steam", "root"),
		filepath.Join(home, ".var", "app", "com.valvesoftware.Steam", ".local", "share", "Steam"),
		filepath.Join(home, "snap", "steam", "common", ".local", "share", "Steam"),
	}
}

func bundledJavaDirs() []string {
	return []string{filepath.Join("jre-linux", "linux64", "bin"), filepath.Join("jre", "bin")}
}

func javaNames() []string {
	return []string{"java"}
}

// run replaces this process with Java, so the server console and signals go straight to it.
func run(java string, args []string, dir string) error {
	if err := os.Chdir(dir); err != nil {
		return err
	}
	return syscall.Exec(java, args, os.Environ())
}

func fail(msg string) {
	fmt.Fprintln(os.Stderr, "Delver Online: "+msg)
	os.Exit(1)
}
