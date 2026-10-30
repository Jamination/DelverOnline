//go:build windows

package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
	"unsafe"
)

func steamRoots() []string {
	var roots []string
	if p := registryString(syscall.HKEY_CURRENT_USER, `Software\Valve\Steam`, "SteamPath"); p != "" {
		roots = append(roots, filepath.FromSlash(p))
	}
	if p := registryString(syscall.HKEY_LOCAL_MACHINE, `SOFTWARE\WOW6432Node\Valve\Steam`, "InstallPath"); p != "" {
		roots = append(roots, p)
	}
	for _, env := range []string{"ProgramFiles(x86)", "ProgramFiles"} {
		if d := os.Getenv(env); d != "" {
			roots = append(roots, filepath.Join(d, "Steam"))
		}
	}
	return roots
}

func registryString(root syscall.Handle, path, name string) string {
	var key syscall.Handle
	if syscall.RegOpenKeyEx(root, syscall.StringToUTF16Ptr(path), 0, syscall.KEY_READ, &key) != nil {
		return ""
	}
	defer syscall.RegCloseKey(key)
	buf := make([]uint16, 1024)
	size := uint32(len(buf) * 2)
	var typ uint32
	if syscall.RegQueryValueEx(key, syscall.StringToUTF16Ptr(name), nil, &typ, (*byte)(unsafe.Pointer(&buf[0])), &size) != nil {
		return ""
	}
	if typ != syscall.REG_SZ && typ != syscall.REG_EXPAND_SZ {
		return ""
	}
	return syscall.UTF16ToString(buf)
}

func bundledJavaDirs() []string {
	return []string{
		filepath.Join("jre-windows", "win64", "bin"),
		filepath.Join("jre-windows", "bin"),
		filepath.Join("jre", "bin"),
		filepath.Join("jre-windows", "win32", "bin"),
	}
}

// The game window needs no console: javaw. The server runs in its console: java.
func javaNames() []string {
	if mode == "server" {
		return []string{"java.exe", "javaw.exe"}
	}
	return []string{"javaw.exe", "java.exe"}
}

// run starts Java and waits for it. The server shares this console; the game logs to client.log in its data folder.
func run(java string, args []string, dir string) error {
	cmd := exec.Command(java, args[1:]...)
	cmd.Dir = dir
	if mode == "server" {
		cmd.Stdin, cmd.Stdout, cmd.Stderr = os.Stdin, os.Stdout, os.Stderr
	} else if log, err := os.Create(filepath.Join(dir, "client.log")); err == nil {
		defer log.Close()
		cmd.Stdout, cmd.Stderr = log, log
	}
	if err := cmd.Start(); err != nil {
		return err
	}
	err := cmd.Wait()
	if exit, ok := err.(*exec.ExitError); ok {
		os.Exit(exit.ExitCode())
	}
	if err != nil {
		return err
	}
	os.Exit(0)
	return nil
}

func fail(msg string) {
	if mode == "server" {
		os.Stderr.WriteString("Delver Online: " + msg + "\r\n")
	} else {
		// the game executable has no console: show the problem in a message box
		box := syscall.NewLazyDLL("user32.dll").NewProc("MessageBoxW")
		text, _ := syscall.UTF16PtrFromString(msg)
		title, _ := syscall.UTF16PtrFromString("Delver Online")
		box.Call(0, uintptr(unsafe.Pointer(text)), uintptr(unsafe.Pointer(title)), 0x10)
	}
	os.Exit(1)
}
