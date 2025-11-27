module myproxy

go 1.24.0

toolchain go1.24.10

// The replace directive is used to point to a specific version or a local fork.
// It's often necessary for gomobile projects to resolve specific build issues.
replace golang.org/x/mobile => golang.org/x/mobile v0.0.0-20251126181937-5c265dc024c4

require (
	golang.org/x/mobile v0.0.0-20251126181937-5c265dc024c4 // indirect
	golang.org/x/mod v0.30.0 // indirect
	golang.org/x/sync v0.18.0 // indirect
	golang.org/x/tools v0.39.0 // indirect
)
