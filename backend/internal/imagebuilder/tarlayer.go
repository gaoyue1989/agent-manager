// 包目录 → 单层 tar（设计 §5）：OAF 配置镜像 = 基底镜像 + 包内容配置层，
// 层内条目统一收敛到根目录 oaf/（OCI 层条目为相对路径，解包后落在容器文件系统 /oaf，
// initContainer 执行 cp -a /oaf/. /config/ 完成配置注入）。
// 安全与确定性约定：
//   - 只收普通文件与目录，符号链接（不跟随）/FIFO/设备/socket 一律跳过（防 zip-slip 同类逃逸，契约 §3.2 步骤 2）；
//   - 目录 mode 0755（非 root 进程遍历需要 x 位）、文件 mode 下限 0644
//     （对齐上传链路「文件最低 0644」约定，backend/AGENTS.md）；
//   - mtime/uid/gid 固定 + filepath.WalkDir 字典序遍历——层内容确定性，
//     同输入同 digest，支撑同 tag 内容寻址幂等重推（设计 §5）。
package imagebuilder

import (
	"archive/tar"
	"bytes"
	"io"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"time"

	v1 "github.com/google/go-containerregistry/pkg/v1"
	"github.com/google/go-containerregistry/pkg/v1/tarball"
)

const (
	// oafLayerRoot 层内根目录名：对应容器内挂载点 /oaf（设计 §5「包内挂载点 /oaf」）。
	oafLayerRoot = "oaf"

	// oafFileMode 文件 mode 下限（backend/AGENTS.md「文件最低 0644」：业务 Pod 非 root 需可读）。
	oafFileMode = 0o644

	// oafDirMode 目录 mode：需要 x 位才能被非 root 进程遍历，0644 目录无法 cd 进入。
	oafDirMode = 0o755
)

// oafLayerModTime 固定 mtime（epoch）：源文件时间戳不进层，保证层 digest 只由内容决定。
// time.Time 非常量故为 var；仅作只读使用，任何位置不得改写。
var oafLayerModTime = time.Unix(0, 0).UTC()

// packageTarLayer 把 OAF 包目录打成单层 v1.Layer（纯内存完成，OAF 包 KB~MB 级，设计 §5）。
// tar 流先写入内存缓冲，再经 tarball.LayerFromOpener 计算 digest/diffID。
// 契约 §3.2 原文为 tarball.LayerFromReader，但 v0.22.1 中该函数已 Deprecated 且内部
// 临时文件不清理（每次构建泄漏一个 /tmp 文件，backend 为常驻进程不可接受）；
// 改用同包 LayerFromOpener + 可重复打开的内存缓冲，语义等价、零磁盘落盘。
func packageTarLayer(packageDir string) (v1.Layer, error) {
	var buf bytes.Buffer
	if err := writePackageTar(&buf, packageDir); err != nil {
		return nil, err
	}
	return tarball.LayerFromOpener(func() (io.ReadCloser, error) {
		return io.NopCloser(bytes.NewReader(buf.Bytes())), nil
	})
}

// writePackageTar 遍历包目录写 tar 流。所有条目相对包目录、前缀 oaf/；
// 遇错即整体失败（含读文件 IO 错误），不产出残缺层。
func writePackageTar(w io.Writer, packageDir string) error {
	tw := tar.NewWriter(w)
	defer tw.Close()

	return filepath.WalkDir(packageDir, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		// 只收普通文件与目录；符号链接不跟随（WalkDir 按 Lstat 语义产出），
		// 其余特殊文件类型一并跳过——上传链路本就拒绝符号链接，此处双保险。
		if !d.IsDir() && !d.Type().IsRegular() {
			return nil
		}
		rel, err := filepath.Rel(packageDir, p)
		if err != nil {
			return err
		}
		name := oafLayerRoot
		if rel != "." {
			name = path.Join(oafLayerRoot, filepath.ToSlash(rel))
		}
		if d.IsDir() {
			// 目录条目按 tar 惯例以 / 结尾
			return tw.WriteHeader(&tar.Header{
				Typeflag: tar.TypeDir,
				Name:     name + "/",
				Mode:     oafDirMode,
				ModTime:  oafLayerModTime,
			})
		}
		info, err := d.Info()
		if err != nil {
			return err
		}
		// 文件 mode 不低于 0644：源文件权限过低（如 0600）抬升到下限，
		// 已有可执行位（≥0644）原样保留
		mode := info.Mode().Perm()
		if mode < oafFileMode {
			mode = oafFileMode
		}
		if err := tw.WriteHeader(&tar.Header{
			Typeflag: tar.TypeReg,
			Name:     name,
			Mode:     int64(mode),
			Size:     info.Size(),
			ModTime:  oafLayerModTime,
		}); err != nil {
			return err
		}
		f, err := os.Open(p)
		if err != nil {
			return err
		}
		defer f.Close()
		_, err = io.Copy(tw, f)
		return err
	})
}
