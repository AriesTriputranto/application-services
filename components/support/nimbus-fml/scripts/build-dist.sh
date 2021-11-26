#!/usr/bin/env bash


targets="x86_64-unknown-linux-musl x86_64-apple-darwin"
dry_run=0

root_dir=../../..
target_dir=$root_dir/target
filename=$(basename $(pwd))
dist_file=${filename}.zip

echo "Installing tools for cross compiling"
install_musl_cross="brew install filosottile/musl-cross/musl-cross"
cargo_clean="cargo clean"
if (( $dry_run == 0 )) ; then
    $install_musl_cross
    $cargo_clean
else
    echo "-> $install_musl_cross"
    echo "-> $cargo_clean"
fi

# Now we need to add a linker

zip_cmd="zip $(pwd)/$dist_file "

for TARGET in $targets ; do
    echo
    echo "Cross compiling for $TARGET"
    rustup="rustup target add $TARGET"
    cargo_build="cargo build --release --target $TARGET"

    if (( $dry_run == 0 )) ; then
        $rustup
        $cargo_build
    else
        echo "-> $rustup"
        echo "-> $cargo_build"
    fi

    zip_cmd="$zip_cmd $TARGET/release/$filename"

done

cmd="(cd $target_dir ; $zip_cmd )"
if (( $dry_run == 0 )) ; then
    (cd $target_dir ; $zip_cmd )
else
    echo "-> $cmd"
fi